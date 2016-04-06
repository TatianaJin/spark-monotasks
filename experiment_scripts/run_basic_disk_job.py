#
# Copyright 2016 The Regents of The University California
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""
This script runs a simple disk experiment that persists a large amount of data to disk and then
reads it back. We vary the number of threads per disk (by changing the configuration parameter
"spark.monotasks.threadsPerDisk") between trials in order to determine the optimal number of threads
per disk for clusters that use SSDs.

See org.apache.spark.examples.monotasks.disk.DiskThroughputExperiment for more information.
"""

import subprocess

import utils

workers = utils.get_workers()
print "Running experiment with %s workers: %s" % (len(workers), workers)

num_partitions = 128
items_per_partition = 3000000
longs_per_item = 6
# The number of times that the test RDD is written and read. Since the JVM is restarted between
# trials, the first few writes and reads of each trial should be treated as warmup.
num_iterations = 10
num_threads_per_disk_values = [1, 2, 4, 8, 16]

spark_defaults_filepath = utils.get_full_path(relative_path="spark/conf/spark-defaults.conf")
copy_dir_command = utils.get_full_path(relative_path="spark-ec2/copy-dir")
stop_all_command = utils.get_full_path(relative_path="spark/sbin/stop-all.sh")
start_all_command = utils.get_full_path(relative_path="spark/sbin/start-all.sh")
run_example_command = utils.get_full_path(relative_path="spark/bin/run-example")
run_on_slaves_command = utils.get_full_path(relative_path="ephemeral-hdfs/sbin/slaves.sh")
clear_cache_command = utils.get_full_path(relative_path="spark-ec2/clear-cache.sh")

for num_threads_per_disk in num_threads_per_disk_values:
  # Change the number of threads per disk by resetting the Spark config.
  change_num_threads_command = ("sed -i \"s/spark\.monotasks\.threadsPerDisk .*/" +
    "spark.monotasks.threadsPerDisk %s/\" %s" % (num_threads_per_disk, spark_defaults_filepath))
  print "Changing the number of threads per disk using command: %s" % change_num_threads_command
  subprocess.check_call(change_num_threads_command, shell=True)

  copy_config_command = "%s --delete %s" % (copy_dir_command, spark_defaults_filepath)
  print "Copying the new configuration to the cluster using command: %s" % copy_config_command
  subprocess.check_call(copy_config_command, shell=True)

  # For consistency, clear the buffer cache before each experiment.
  clear_slave_cache_command = "%s %s" % (run_on_slaves_command, clear_cache_command)
  print "Clearing the OS buffer cache using command: %s" % clear_slave_cache_command
  subprocess.check_call(clear_slave_cache_command, shell=True)

  subprocess.check_call(start_all_command, shell=True)
  parameters = [
    num_partitions,
    items_per_partition,
    longs_per_item,
    num_iterations]
  stringified_parameters = ["%s" % p for p in parameters]
  experiment_command = ("%s monotasks.disk.DiskThroughputExperiment %s" %
    (run_example_command, " ".join(stringified_parameters)))
  print "Running experiment using command: %s" % experiment_command
  subprocess.check_call(experiment_command, shell=True)

  # Stop Spark in order to finalize the logs.
  subprocess.check_call(stop_all_command, shell=True)
  utils.copy_and_zip_all_logs(stringified_parameters + [str(num_threads_per_disk)], workers)
