.. Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
   implied.  See the License for the specific language governing
   permissions and limitations under the License.


.. _performance-tuning:

##################
Performance Tuning
##################

.. contents:: Table of Contents

**********
Split Jobs
**********

Hydra has the capability to ingest, process, and store data streams within the cluster.  The typical use case for this
is that some external system is generating data that we would like to process inside of Hydra.  Hydra clusters may have
10s or 100s of machines so we need to prepare the data in such a way that the large Hydra cluster can consume the data
efficiently.  In simple terms Hydra Split Jobs consume rows of data and emit rows of data.  Split jobs may transform
the data they consume but it is not required.  It is very common for split jobs to store the data in n *shards*.
A *shard* is a term used to define a partition of the source data.  There are several things to consider when
creating Hydra split jobs.

* The most important thing to consider is the *shard key* This determines who the data is partitioned. See section on shard key selection below for more information on how to select your key
* The number of shards to emit data into.  This determines the amount of parallelism downstream jobs may achieve so typical values are 128 or 256
* The directory structure to store the output data in.  Typically we store data into dated subdirectories. For example ``20121107``.  This way downstream jobs can select which time buckets to process without having to scan through every file that the split job has ever created.
* The compression type to use.  By default split jobs use gz compression but other options are available
* Which transformations to apply to the input data.  Transformations may include converting the time field, dropping invalid records, creating multiple output lines for each input line, or any other filter operation that may be useful to downstream jobs.

======================================
Best Practices for Creating Split Jobs
======================================

---------------------
Selecting a Shard Key
---------------------

The most important decision the designer of a split job makes is the selection of the shard key. This will influence
how evenly the data is distributed in the cluster, the efficiency of downstream jobs to process data, and the
efficiency of queries that run against downstream jobs.  Lets consider each of these elements individually.

^^^^^^^^^^^^^^^^^
Data Distribution
^^^^^^^^^^^^^^^^^

When selecting your shard key (can also be called partition) you should consider how it will impact data
distribution among the output files. In an ideal world each output file has exactly the same amount of data.
This means that each downstream task processing data generated by your split job would have an equal amount of work to do.

Suppose you pick _domain_ as the shard key.  There may be very good reasons to shard data this way. For example your
downstream job may need all of the data for a given domain on a single node.  Sharding by domain is the only
way to accomplish that.  However sharding this way will create an unbalanced data distribution.
Consider www.cnn.com vs www.yourfriendsblog.com.  CNN is going to have a ton of data but your friend's blog will only
have small amount.

^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Efficiency of Downstream Jobs
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Efficiency of downstream jobs is impacted by data distribution.  Imagine that you have two shards and one shard gets
99% of the data.  Since your downstream job can only run 2 tasks (since there are only 2 shards) than 1 task (process)
will take 99% longer than the other task to complete.  This can lead to the `long tail`_
problem where your system has spare capacity but it cannot be used because of the data imbalance.

.. _long tail: http://en.wikipedia.org/wiki/Long_tail

Another factor in downstream job efficiency is dependent on the choice of shard_key and the structure of the map job
that is consuming the split data.  Consider a map job that creates a tree that stores the top 100 URLs for each domain.
If the input data is sharded by domain than every task (database) in the map job will only have the domains for the
shard keys that it is processing.  For example if there are two partitions and 100 domains than each partition will
have 50 domains.  By sharding the data this way it makes it feasible that the map jobs will be able to fit each
individual database into memory and the resulting job runtime will be much faster than if it were to spill to disk.
If you took the same data and sharded by a key other than domain than each partition would have all 100 domains.
Taken to extremes if you have a job that stores data by domain and you process our full data set (15 million domains
at this time) then every database has all 15M domains.

The depth of your tree is a critical factor in the performance of the map job.  For this reason it often makes sense
to split the same raw data multiple times but sharded by different keys for different consumers.

^^^^^^^^^^^^^^^^
Query Efficiency
^^^^^^^^^^^^^^^^

Most event data has some sort of user ID associated with it.  This is a generally random number so choosing UID as your shard key will create a
basically even distribution which is good in many use cases.  Sharding by UID is especially helpful when you know
that queries will be run that look for individual UIDs.  If you know that the data has been sharded by UID than when
you create UID queries you do not need to join the result sets for those queries.  Since each task (database) is
guaranteed to have all of the information about a given UID than you can run your queries without aggregating results.

For example image you want to know how many times a given user was seen in our logs.  If you know the data is sharded
by UID than you can run the following query with no local ops to get the result:

::

    query = /some/path/+UID:+hits
    
If the data is sharded by a key other than UID you would need a query like:

::

    query = /some/path/+UID:+hits
    ops = gather=ks

This isn't so bad if there are small amount of results but imagine you get 100M results.  Having to sort and merge
100M results on the query master takes some time.  So by thinking about the shard key you can make the query
orders of magnitude faster.

Another thing about understanding the partition key is that you can take advantage of bloom filters and fast fail to
improve the efficiency of a query.  For example if you know the data is sharded by id than you can put a
bloom filter at the top of your map job that tracks ids.  When you run your query you can check that bloom filter
for the pub_id you are searching for and if the bloom does not detect your pub it fails immediately without having to
descend into the child nodes (possibly millions of them) to find the key you are looking for.  If you have 128 tasks
and data sharded by id only one of them would have to do anything more than a trivial amount of work.

-----------------
Multiplex Is Good
-----------------

We can't make multiplex:true the default because it may break legacy jobs
but you should always use multiplex:true in new split jobs.  This parameter
uses the Muxy project to write a small number of large files that can
represent a very large number of small files.  This reduces the pressure on
the OS to maintain the file handles and improves performance.  Multiplexing also
helps with replication because the number of files to transfer is
directly proportional to the efficiency of the rsyncs we run to
replicate data in the cluster.  Think about a split job that has 256 shards and the data is stored by hour.
In that case each day of data has a minimum of 2400 files.  That is a lot of overhead for rsync to deal with.
Using multiplex would reduce the minimum number of files from 2400 to 24.

Here is an example of a split configuration with multiplex enabled:

::

    output:{
		type:"file",
		path:["{{DATE_YMD}}","/","/","{{SHARD}}"],
		writer:{
			maxOpen:1024,
			flags:{
				noAppend:false,
				maxSize:"64M",
				compress:true,
			},
			factory:{
			    dir:"split",
			    multiplex:true,
	        },
			format:{
				type:"channel",
				exclude:["VALID","CLICK","SHARD","DATE","EVT","TMP_EMAIL_EVENT_TYPE","SHARD2"],
			},
		},
    },

Notice the multiplex:true component in the code block above.

----------------------------------------------
Do not store data by hour (unless you have to)
----------------------------------------------

Storing data by hour is helpful in a small number of special cases.
View data is the primary example where the data volume is so huge and
its helpful to be able to process just one hour at a time without
scanning other data files.

For smaller datasets like share, it only increases the accounting
overhead.  If you have 100 shards and the data is stored by hour a
single day will have 2400 files.  Even if we use mux more files are
more expensive.  

In general, a reasonable path for your split data looks something like:

.. code-block:: javascript

    path:["{{DATE_YMD}}","/","/","{{SHARD}}"],

---------------------------------------------
Think about the maximum degree of parallelism
---------------------------------------------

The number of shards you choose for a split is the limiting factor for
the number of parallel tasks you can run against the job.  With muxy
and smart choices for the directory tree it is relatively inexpensive to
run splits with 128 or 256 shards.   If you choose a small number of
shards you may be forced to re-split the data in the future if it
turns out you need to run more tasks in parallel to meet your
performance goals.

Think about it this way.  If your split job has 10 shards than each task in your split job will store 10 files per
directory.  When another job attempts to consume data from your job it will need to process all 10 files in each
directory.  So if the consuming job has 1 degree of parallelism than the single task will consume all 10 files.
If the consuming job has 5 degrees of parallelism than the 5 tasks will each consume 2 files from the directory.
But what if the consuming job has 20 degrees of parallelism?  In that case the first 10 tasks would each consume 1 file
but there would be nothing left for the 11th-20th tasks to process.  This is why it is important to create enough
shards in your split job so that if you want to you could run the job in a highly parallel way.

-------------------------------
Compression algorithm selection
-------------------------------

The default compression algorithm for split jobs is zip.  This
provides the best compression ratio but the highest CPU cost.  If you
can afford to take extra disk space than you should use snappy or LZF
compression for higher performance.  Your compression decision impacts
the performance of the split job and the performance of downstream
jobs that need to decompress the data written by the split.

The ``writer`` section of the split output configuration allows you to override the compression algorithm used by the
split file.  If you do nothing the default compression type is GZ.  This table shows the different compression
types available for split jobs:

================  ====  ================================   =============================================================
Compression Type  Code  INFO                               Notes
================  ====  ================================   =============================================================
GZ                0     http://www.gzip.org/               Best compression ratio but inefficient in terms of CPU usage
LZF               1     https://github.com/ning/compress   Fast and CPU efficient but compression is not as good as GZ
SNAPPY            2     http://code.google.com/p/snappy/   Very fast (fastest) but subpar compression
BZIP2             3     http://www.bzip.org                Fast decompression
================  ====  ================================   =============================================================

Here is an example of a split that uses the default compression:

.. code-block:: javascript

    output:{
		type:"file",
		path:["{{DATE_YMD}}","/","/","{{SHARD}}"],
		writer:{
			maxOpen:1024,
			flags:{
				noAppend:false,
				maxSize:"64M",
				compress:true,
			},
			factory:{
			    dir:"split",
			    multiplex:true,
	        },
			format:{
				type:"channel",
				exclude:["VALID","CLICK","SHARD","DATE","EVT","TMP_EMAIL_EVENT_TYPE","SHARD2"],
			},
		},
    },
    
And here is an example of the same split configuration but this one uses LZF:

.. code-block:: javascript

    output:{
		type:"file",
		path:["{{DATE_YMD}}","/","/","{{SHARD}}"],
		writer:{
			maxOpen:1024,
			flags:{
				noAppend:false,
				maxSize:"64M",
				compress:true,
				compressType:1,
			},
			factory:{
			    dir:"split",
			    multiplex:true,
	        },
			format:{
				type:"channel",
				exclude:["VALID","CLICK","SHARD","DATE","EVT","TMP_EMAIL_EVENT_TYPE","SHARD2"],
			},
		},
    },

------------------------
Appending to files is OK
------------------------

For a time it was recommended that split jobs use noAppend:true.  Now there is only one use case where this is required.
That is when we need to guarantee that generated files will never change.  This is the case when
we create data files for consumption by third parties.  They need to know that once they've downloaded a file they
never need to do so again.  In other other cases noAppend:false should be used.  This means that if new data is
received at a later time to the same output location then data is appended to the existing
file rather than creating a new file.  This prevents the explosion in the number of files maintained and makes
the entire system more efficient.  It used to be the case that we wanted to avoid scanning through very large files
to find a small amount of new data at the end of it but with mux that is no longer a problem.
