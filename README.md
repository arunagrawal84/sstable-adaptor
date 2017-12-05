# SSTable Adaptor

## Overview

SSTable Adaptor is a thin library stripped out of Cassandra code base that can be injected into any JVM languages to read/write SSTable formatted data.  Furthermore, out of the box, it can read/write data from/to various data storage such as S3, HDFS, local disk, etc.

SSTable Adaptor Features:

- Read/write on SSTable 3.X and read on SSTable 2.X files including compacting SSTable files
- Use this in a standalone Java or a JVM language program
- Read/Write data on S3, HDFS, or local disk
- Use it in Spark (we will open-source our Spark job soon)
- On-the-fly compaction reader
- Random seek reading on a big SSTable file

## Packages

- sstable-adaptor-core: The core I/O library.
- sstable-adaptor-cassandra: Mainly code borrowed from Cassandra project to read/write SSTable files and Hadoop Filesystem

## Binaries

Currently, we build and distribute to our private repo.  We will adjust this and distribute to Maven/Jcenter soon.


## Programmer's Guide

TBA

## LICENSE

Copyright 2017 Netflix, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

<http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
