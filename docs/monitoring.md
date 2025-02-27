---
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements. See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License. You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---


Monitoring
===

There are two ways to monitor Celeborn cluster: prometheus metrics and REST API.

# Metrics

Celeborn has a configurable metrics system based on the
[Dropwizard Metrics Library](http://metrics.dropwizard.io/4.2.0).
This allows users to report Celeborn metrics to a variety of sinks including HTTP, JMX, CSV
files and prometheus servlet. The metrics are generated by sources embedded in the Celeborn code base.
They provide instrumentation for specific activities and Celeborn components.
The metrics system is configured via a configuration file that Celeborn expects to be present
at `$CELEBORN_HOME/conf/metrics.properties`. A custom file location can be specified via the
`spark.metrics.conf` [configuration property](https://celeborn.apache.org/configuration/#metrics).
Instead of using the configuration file, a set of configuration parameters with prefix
`celeborn.metrics.conf.` can be used.

Celeborn's metrics are decoupled into two
_instances_ corresponding to Celeborn components.  The following instances are currently supported:

* `master`: The Celeborn cluster master process.
* `worker`: A Celeborn cluster worker process.

Each instance can report to zero or more _sinks_. Sinks are contained in the
`org.apache.celeborn.common.metrics.sink` package:

* `CSVSink`: Exports metrics data to CSV files at regular intervals.
* `PrometheusServlet`: Adds a servlet within the existing Celeborn REST API to serve metrics data in Prometheus format.
* `GraphiteSink`: Sends metrics to a Graphite node.

The syntax of the metrics configuration file and the parameters available for each sink are defined
in an example configuration file,
`$CELEBORN_HOME/conf/metrics.properties.template`.

When using Celeborn configuration parameters instead of the metrics configuration file, the relevant
parameter names are composed by the prefix `celeborn.metrics.conf.` followed by the configuration
details, i.e. the parameters take the following form:
`celeborn.metrics.conf.[instance|*].sink.[sink_name].[parameter_name]`.
This example shows a list of Celeborn configuration parameters for a CSV sink:
```
"celeborn.metrics.conf.*.sink.csv.class"="org.apache.celeborn.common.metrics.sink.GraphiteSink"
"celeborn.metrics.conf.*.sink.csv.period"="1"
"celeborn.metrics.conf.*.sink.csv.unit"=minutes
"celeborn.metrics.conf.*.sink.csv.directory"=/tmp/
```

Default values of the Celeborn metrics configuration are as follows:
```
*.sink.prometheusServlet.class=org.apache.celeborn.common.metrics.sink.PrometheusServlet
```

Additional sources can be configured using the metrics configuration file or the configuration
parameter `spark.metrics.conf.[component_name].source.jvm.class=[source_name]`. At present the
no source is the available optional source. For example the following configuration parameter
activates the Example source:
`"celeborn.metrics.conf.*.source.jvm.class"="org.apache.celeborn.common.metrics.source.ExampleSource"`

## List of available metrics providers

Metrics used by Celeborn are of multiple types: gauge, counter, histogram, meter and timer,
see [Dropwizard library documentation for details](https://metrics.dropwizard.io/4.2.0/getting-started.html).
The following list of components and metrics reports the name and some details about the available metrics,
grouped per component instance and source namespace.
The most common time of metrics used in Celeborn instrumentation are gauges and counters.
Counters can be recognized as they have the `.count` suffix. Timers, meters and histograms are annotated
in the list, the rest of the list elements are metrics of type gauge.
The large majority of metrics are active as soon as their parent component instance is configured,
some metrics require also to be enabled via an additional configuration parameter, the details are
reported in the list.

### Component instance = Master
These metrics are exposed by Celeborn master.

  - namespace=master 
    - WorkerCount
    - LostWorkers
    - BlacklistedWorkerCount
    - RegisteredShuffleCount
    - IsActiveMaster
    - PartitionSize
      - The size of estimated shuffle partition size.
    - OfferSlotsTime
      - The time for a master to process offers slots request by register shuffle.

  - namespace=CPU
    - JVMCPUTime

  - namespace=JVM
    - This source provides information on JVM metrics using the
    [Dropwizard/Codahale Metric Sets for JVM instrumentation](https://metrics.dropwizard.io/4.2.0/manual/jvm.html)
    and in particular the metric sets BufferPoolMetricSet, GarbageCollectorMetricSet and MemoryUsageGaugeSet.

  - namespace=ResourceConsumption
    - **notes:**
      - This merics data is generated for each user and they are identified using a metric tag. 
    - diskFileCount
    - diskBytesWritten
    - hdfsFileCount
    - hdfsBytesWritten

### Component instance = Worker
These metrics are exposed by Celeborn worker.

  - namespace=worker
    - CommitFilesTime
      - The time for a worker to flush buffers and close files related to specified shuffle.
    - ReserveSlotsTime
    - FlushDataTime
      - The time for a worker to write a buffer which is 256KB by default to storage.
    - OpenStreamTime
      - The time for a worker to process openStream RPC and return StreamHandle.
    - FetchChunkTime
      - The time for a worker to fetch a chunk which is 8MB by default from a reduced partition. 
    - MasterPushDataTime
      - The time for a worker to handle a pushData RPC sent from a celeborn client.
    - SlavePushDataTime
      - The time for a worker to handle a pushData RPC sent from a celeborn worker by replicating.
    - WriteDataFailCount
    - ReplicateDataFailCount
    - ReplicateDataWriteFailCount
    - ReplicateDataCreateConnectionFailCount
    - ReplicateDataConnectionExceptionCount
    - ReplicateDataTimeoutCount
    - PushDataHandshakeFailCount
    - RegionStartFailCount
    - RegionFinishFailCount
    - MasterPushDataHandshakeTime
    - SlavePushDataHandshakeTime
    - MasterRegionStartTime
    - SlaveRegionStartTime
    - MasterRegionFinishTime
    - SlaveRegionFinishTime
    - TakeBufferTime
      - The time for a worker to take out a buffer from a disk flusher.
    - RegisteredShuffleCount
    - SlotsAllocated
    - NettyMemory
      - The total amount of off-heap memory used by celeborn worker.
    - SortTime
      - The time for a worker to sort a shuffle file.
    - SortMemory
      - The memory used by sorting shuffle files.
    - SortingFiles
    - SortedFiles
    - SortedFileSize
    - DiskBuffer
      - The memory occupied by pushData and pushMergedData which should be written to disk.
    - PausePushData
      - The count for a worker to stop receiving pushData from clients because of back pressure.
    - PausePushDataAndReplicate
      - The count for a worker to stop receiving pushData from clients and other workers because of back pressure.
    - BufferStreamReadBuffer
      - The memory used by credit stream read buffer.
    - ReadBufferDispatcherRequestsLength
      - The queue size of read buffer allocation requests.
    - ReadBufferAllocatedCount
      - Allocated read buffer count.
    - CreditStreamCount
      - Stream count for map partition reading streams.
    - ActiveMapPartitionCount
    - DeviceOSFreeCapacity(B)
    - DeviceOSTotalCapacity(B)
    - DeviceCelebornFreeCapacity(B)
    - DeviceCelebornTotalCapacity(B)
    - PotentialConsumeSpeed
    - UserProduceSpeed

  - namespace=CPU
    - JVMCPUTime

  - namespace=JVM
    - This source provides information on JVM metrics using the
      [Dropwizard/Codahale Metric Sets for JVM instrumentation](https://metrics.dropwizard.io/4.2.0/manual/jvm.html)
      and in particular the metric sets BufferPoolMetricSet, GarbageCollectorMetricSet and MemoryUsageGaugeSet.

# REST API

In addition to viewing the metrics, Celeborn also support REST API. This gives developers
an easy way to create new visualizations and monitoring tools for Celeborn and
also easy for users to get the running status of the service. The REST API is available for
both master and worker. The endpoints are mounted at `host:port`. For example,
for the master, they would typically be accessible
at `http://<master-prometheus-host>:<master-prometheus-port><path>`, and
for the worker, at `http://<worker-prometheus-host>:<worker-prometheus-port><path>`.

The configuration of `<master-prometheus-host>`, `<master-prometheus-port>`, `<worker-prometheus-host>`, `<worker-prometheus-port>` as below:

| Key                                     | Default | Description                | Since |
|-----------------------------------------|---------|----------------------------|-------|
| celeborn.master.metrics.prometheus.host | 0.0.0.0 | Master's Prometheus host.  | 0.2.0 |
| celeborn.master.metrics.prometheus.port | 9098    | Master's Prometheus port.  | 0.2.0 |
| celeborn.worker.metrics.prometheus.host | 0.0.0.0 | Worker's Prometheus host.  | 0.2.0 |
| celeborn.worker.metrics.prometheus.port | 9096    | Worker's Prometheus port.  | 0.2.0 |

API path listed as below:

| Path                       | Service         | Meaning                                                                                                                                                                              |
|----------------------------|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| /metrics/prometheus        | master, worker  | List service metrics data in prometheus format.                                                                                                                                      |
| /conf                      | master, worker  | List the conf setting of the service.                                                                                                                                                |
| /workerInfo                | master, worker  | List worker information of the service. For the master, it will list all registered workers 's information.                                                                          |
| /lostWorkers               | master          | List all lost workers of the master.                                                                                                                                                 |
| /blacklistedWorkers        | master          | List all  blacklisted workers of the master.                                                                                                                                         |
| /threadDump                | master, worker  | List the current thread dump of the service.                                                                                                                                         |
| /hostnames                 | master          | List all running application's LifecycleManager's hostnames of the cluster.                                                                                                          |
| /applications              | master          | List all running application's ids of the cluster.                                                                                                                                   |
| /shuffles                  | master, worker  | List all running shuffle keys of the service. For master, will return all running shuffle's key of the cluster, for worker, only return keys of shuffles running in that worker.     |
| /listTopDiskUsedApps       | master, worker  | List the top disk usage application ids. For master, will return the top disk usage application ids for the cluster, for worker, only return application ids running in that worker. |
| /listPartitionLocationInfo | worker          | List all living PartitionLocation information in that worker.                                                                                                                        |
| /unavailablePeers          | worker          | List the unavailable peers of the worker, this always means the worker connect to the peer failed.                                                                                   |
| /isShutdown                | worker          | Show if the worker is during the process of shutdown.                                                                                                                                |
| /isRegistered              | worker          | Show if the worker is registered to the master success.                                                                                                                              |