/*
 * Copyright 2014 The Regents of The University California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.monotasks.disk

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.HashMap
import scala.util.control.NonFatal

import org.apache.spark.{Logging, SparkConf, SparkEnv, SparkException}
import org.apache.spark.monotasks.TaskSuccess
import org.apache.spark.storage.BlockFileManager
import org.apache.spark.util.{SparkUncaughtExceptionHandler, Utils}

/**
 * Schedules DiskMonotasks for execution on the appropriate disk. If the DiskMonotask is writing a
 * block, then the DiskScheduler determines which disk that block should be written to. The
 * configuration parameter "spark.monotasks.threadsPerDisk" determines the maximum number of
 * DiskMonotasks that will execute concurrently on each disk.
 */
private[spark] class DiskScheduler(
    blockFileManager: BlockFileManager,
    conf: SparkConf) extends Logging {

  // Mapping from disk identifier to the corresponding DiskAccessor object.
  private val diskAccessors = new HashMap[String, DiskAccessor]()
  /* A list of unique identifiers for all of this worker's physical disks. Assumes that each path in
   * the user-defined list of Spark local directories (spark.local.dirs or SPARK_LOCAL_DIRS, if it
   * is set) describes a separate physical disk. */
  val diskIds = blockFileManager.localDirs.keySet.toArray
  /**
   * Queue of HdfsWriteMonotasks that have been submitted to the scheduler but haven't yet been
   * submitted to a DiskAccessor, because we first need to determine which disk the monotask will
   * access.
   */
  private val hdfsWriteMonotaskQueue = new LinkedBlockingQueue[HdfsWriteMonotask]()

  addShutdownHook()
  buildDiskAccessors(conf)
  launchHdfsWriteMonotaskSubmitterThread()

  /**
   * Builds a DiskAccessor object with a user-defined concurrency for each disk identifier.
   *
   * TODO: Currently, all DiskAccessors are created with the same degree of concurrency. It may be
   *       interesting to implement a more advanced design that allows the user to specify a
   *       different concurrency for each disk. This would be useful for systems that use both SSDs
   *       and HDDs.
   */
  private def buildDiskAccessors(conf: SparkConf) {
    val numThreadsPerDisk = conf.getInt("spark.monotasks.threadsPerDisk", 1)
    diskIds.foreach { diskId =>
      logInfo(s"Creating $numThreadsPerDisk threads for disk $diskId")
      diskAccessors(diskId) = new DiskAccessor(diskId, numThreadsPerDisk)
    }
  }

  /**
   * Launches a daemon thread responsible for determining which disk HdfsWriteMonotasks should run
   * on. This is done in a separate thread, rather than in the scheduler's main thread, because it
   * requires writing a small amount of data to disk, so it takes a non-trivial amount of time.
   *
   * This small write may interfere with disk monotasks that are currently running; we can't avoid
   * this because we don't know what disk these monotasks will run on until we've written a small
   * amount of data.  To mitigate the effect of this, we don't sync these writes to disk, so they
   * should typically remain in the buffer cache.
   */
  private def launchHdfsWriteMonotaskSubmitterThread(): Unit = {
    val submitter = new Thread(new Runnable() {
      override def run(): Unit = {
        while (true) {
          val hdfsWriteMonotask = hdfsWriteMonotaskQueue.take()

          try {
            hdfsWriteMonotask.initialize()
            addToDiskQueue(hdfsWriteMonotask, hdfsWriteMonotask.chooseLocalDir(diskIds))
          } catch {
            case NonFatal(e) => hdfsWriteMonotask.handleException(e)
          }
        }
      }
    })
    submitter.setDaemon(true)
    submitter.setName("HdfsWriteMonotask submitter")
    submitter.start()
  }

  /**
   * Returns a mapping of physical disk name to the number of running and queued disk monotasks on
   * that disk.
   */
  def getDiskNameToNumRunningAndQueuedDiskMonotasks: HashMap[String, Int] = {
    diskAccessors.map {
      case (_, diskAccessor) =>
        (diskAccessor.diskName, diskAccessor.getNumRunningAndQueuedDiskMonotasks)
    }
  }

  /**
   * Looks up the disk on which the provided DiskMonotask will operate, and adds it to that disk's
   * task queue.
   */
  def submitTask(task: DiskMonotask): Unit = {
    // Extract the identifier of the disk on which this task will operate.
    val diskId = task match {
      case write: DiskWriteMonotask =>
        diskIds.foreach(addToDiskQueue(task, _))
        return

      case read: DiskReadMonotask =>
        read.diskId

      case remove: DiskRemoveMonotask =>
        remove.diskId

      case hdfsWrite: HdfsWriteMonotask =>
        hdfsWriteMonotaskQueue.put(hdfsWrite)
        return

      case hdfs: HdfsDiskMonotask =>
        hdfs.chooseLocalDir(diskIds)

      case _ =>
        val exception = new SparkException(
          s"DiskMonotask $task (id: ${task.taskId}) rejected because its subtype is not supported.")
        task.handleException(exception)
        return
    }
    addToDiskQueue(task, diskId)
  }

  private def addToDiskQueue(monotask: DiskMonotask, diskId: String): Unit = {
    if (diskIds.contains(diskId)) {
      diskAccessors(diskId).submitMonotask(monotask)
    } else {
      val exception = new SparkException(s"DiskMonotask $monotask (id: ${monotask.taskId}) has " +
        s"an invalid diskId: $diskId. Valid diskIds are: ${diskIds.mkString(", ")}")
      monotask.handleException(exception)
    }
  }

  private def addShutdownHook() {
    Runtime.getRuntime.addShutdownHook(new Thread("Stop DiskAccessors") {
      override def run() = Utils.logUncaughtExceptions(DiskScheduler.this.stop())
    })
  }

  /** Triggers all DiskAccessors to stop executing, interrupting any in-progress DiskMonotasks. */
  def stop(): Unit = {
    diskAccessors.values.foreach(_.stop())
  }

  /**
   * Executes the provided monotask and catches any exceptions that it throws. Communicates to the
   * LocalDagScheduler whether the monotask completed successfully or failed.
   */
  private def executeMonotask(monotask: DiskMonotask): Unit = {
    val startTimeNanos = System.nanoTime
    try {
      monotask.execute()
      monotask.context.taskMetrics.incDiskNanos(System.nanoTime - startTimeNanos)

      logDebug(s"DiskMonotask $monotask (id: ${monotask.taskId}) operating on " +
        s"block ${monotask.blockId} completed successfully.")
      SparkEnv.get.localDagScheduler.post(TaskSuccess(monotask))
    } catch {
      case t: Throwable => {
        // An exception was thrown, causing the currently-executing DiskMonotask to fail. Attempt to
        // exit cleanly by informing the LocalDagScheduler of the failure. If this was a fatal
        // exception, we will delegate to the default uncaught exception handler, which will
        // terminate the Executor. We don't forcibly exit unless the exception was inherently fatal
        // in order to avoid stopping other tasks unnecessarily.
        if (Utils.isFatalError(t)) {
          SparkUncaughtExceptionHandler.uncaughtException(t)
        }
        if (t.isInstanceOf[ClosedByInterruptException]) {
          logDebug(s"During normal shutdown, thread '${Thread.currentThread().getName()}' was " +
            "interrupted while performing I/O.")
        }

        // Set the disk monotask time, even when a failure occurred. This cannot be done in a
        // finally clause, because the disk time needs to be set before the LocalDagScheduler is
        // notified of the task's completion.
        monotask.context.taskMetrics.incDiskNanos(System.nanoTime - startTimeNanos)
        monotask.handleException(t)
      }
    }
  }

  /**
   * Stores and services the DiskMonotask queue for the specified disk.
   *
   * @param concurrency The maximum number of DiskMonotasks that will be executed concurrently on
   *                    each disk.
   */
  private class DiskAccessor(diskId: String, concurrency: Int) extends Runnable {

    // The name of the physical disk on which this DiskAccessor will operate.
    val diskName = BlockFileManager.getDiskNameFromPath(diskId)

    // A queue of DiskMonotasks that are waiting to be executed.
    private val taskQueue = new LinkedBlockingQueue[DiskMonotask]()

    private val numRunningAndQueuedDiskMonotasks = new AtomicInteger(0)

    // An array of threads that are concurrently executing DiskMonotasks from this DiskAccessor's
    // task queue.
    private val diskAccessorThreads = (1 to concurrency).map { threadIndex =>
      val diskAccessorThread = new Thread(this)
      diskAccessorThread.setDaemon(true)
      diskAccessorThread.setName(
        s"DiskAccessor thread $threadIndex of $concurrency for disk $diskId")
      diskAccessorThread.start()
      diskAccessorThread
    }

    def submitMonotask(monotask: DiskMonotask): Unit = {
      taskQueue.put(monotask)
      numRunningAndQueuedDiskMonotasks.incrementAndGet()
    }

    // TODO: Write monotasks get put in the queue for all disks, so this
    //       includes some monotasks that are also queued on other disks. Fix this.
    def getNumRunningAndQueuedDiskMonotasks: Int = numRunningAndQueuedDiskMonotasks.get()

    /** Continuously executes DiskMonotasks from the task queue. */
    def run(): Unit = {
      val currentThread = Thread.currentThread()
      val threadName = currentThread.getName()

      try {
        while (!currentThread.isInterrupted()) {
          val diskMonotask = taskQueue.take()
          val monotaskAssigned = diskMonotask match {
            case write: DiskWriteMonotask =>
              write.assignToDisk(diskId)
            case _ =>
              true
            }
          if (monotaskAssigned) {
            diskMonotask.context.taskMetrics.incDiskWaitNanos(diskMonotask.getQueueTime())
            executeMonotask(diskMonotask)
          }
          numRunningAndQueuedDiskMonotasks.decrementAndGet()
        }
      } catch {
        case _: InterruptedException => {
          // An InterruptedException was thrown during the call to taskQueue.take(), above. Since
          // throwing an InterruptedException clears a thread's interrupt status, it is proper form
          // to reset the interrupt status by calling Thread.currentThread().interrupt() after
          // catching an InterruptedException so that other code knows that this thread has been
          // interrupted.
          //
          // This try/catch block is not strictly necessary, since we want the run() method to end
          // when an InterruptedException is thrown. It is included so that the DiskAccessor stops
          // cleanly (and does not throw a benign InterruptedException that is visible to the user).
          logDebug(s"Thread '$threadName' interrupted while waiting for more DiskMonotasks.")
          currentThread.interrupt()
        }
      }
    }

    /**
     * Interrupts all of this DiskAccessor's threads, causing them to stop executing DiskMonotasks
     * and expire. During normal operation, this method will be called once all DiskMonotasks have
     * been executed and the DiskScheduler is ready to shut down. In that case, all of the threads
     * will be interrupted while waiting for more tasks to be added to the task queue. This is
     * harmless. However, if any threads are still executing tasks, this will interrupt them before
     * they finish.
     */
    def stop(): Unit = {
      diskAccessorThreads.foreach { diskAccessorThread =>
        logDebug(s"Attempting to stop thread: ${diskAccessorThread.getName()}")
        diskAccessorThread.interrupt()
      }
    }
  }
}