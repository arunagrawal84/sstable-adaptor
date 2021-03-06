/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import com.google.common.base.Charsets;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Sets;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.BufferDecoratedKey;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.RowIndexEntry;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.io.util.DiskOptimizationStrategy;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.io.util.HadoopFileUtils;
import org.apache.cassandra.io.util.RandomAccessReader;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.memory.HeapAllocator;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * This class is built on top of the SequenceFile. It stores
 * data on disk in sorted fashion. However the sorting is upto
 * the application. This class expects keys to be handed to it
 * in sorted order.
 *
 * A separate index file is maintained as well, containing the
 * SSTable keys and the offset into the SSTable at which they are found.
 * Every 1/indexInterval key is read into memory when the SSTable is opened.
 *
 * Finally, a bloom filter file is also kept for the keys in each SSTable.
 */
public abstract class SSTable
{
    static final Logger logger = LoggerFactory.getLogger(SSTable.class);

    public static final int TOMBSTONE_HISTOGRAM_BIN_SIZE = 100;

    public final Descriptor descriptor;
    protected final Set<Component> components;
    public final CFMetaData metadata;
    public final boolean compression;

    public DecoratedKey first;
    public DecoratedKey last;

    protected final DiskOptimizationStrategy optimizationStrategy;

    protected SSTable(Descriptor descriptor, Set<Component> components, CFMetaData metadata, DiskOptimizationStrategy optimizationStrategy)
    {
        // In almost all cases, metadata shouldn't be null, but allowing null allows to create a mostly functional SSTable without
        // full schema definition. SSTableLoader use that ability
        assert descriptor != null;
        assert components != null;
        assert metadata != null;

        this.descriptor = descriptor;
        Set<Component> dataComponents = new HashSet<>(components);
        this.compression = dataComponents.contains(Component.COMPRESSION_INFO);
        this.components = new CopyOnWriteArraySet<>(dataComponents);
        this.metadata = metadata;
        this.optimizationStrategy = Objects.requireNonNull(optimizationStrategy);
    }

    /**
     * We use a ReferenceQueue to manage deleting files that have been compacted
     * and for which no more SSTable references exist.  But this is not guaranteed
     * to run for each such file because of the semantics of the JVM gc.  So,
     * we write a marker to `compactedFilename` when a file is compacted;
     * if such a marker exists on startup, the file should be removed.
     *
     * This method will also remove SSTables that are marked as temporary.
     *
     * @return true if the file was deleted
     */
    public static boolean delete(Descriptor desc, Set<Component> components)
    {
        // remove the DATA component first if it exists
        if (components.contains(Component.DATA))
            FileUtils.deleteWithConfirm(desc.filenameFor(Component.DATA));
        for (Component component : components)
        {
            if (component.equals(Component.DATA) || component.equals(Component.SUMMARY))
                continue;

            FileUtils.deleteWithConfirm(desc.filenameFor(component));
        }

        if (components.contains(Component.SUMMARY))
            FileUtils.delete(desc.filenameFor(Component.SUMMARY));

        logger.trace("Deleted {}", desc);
        return true;
    }

    public IPartitioner getPartitioner()
    {
        return metadata.partitioner;
    }

    public DecoratedKey decorateKey(ByteBuffer key)
    {
        return getPartitioner().decorateKey(key);
    }

    /**
     * If the given @param key occupies only part of a larger buffer, allocate a new buffer that is only
     * as large as necessary.
     */
    public static DecoratedKey getMinimalKey(DecoratedKey key)
    {
        return key.getKey().position() > 0 || key.getKey().hasRemaining() || !key.getKey().hasArray()
                                       ? new BufferDecoratedKey(key.getToken(), HeapAllocator.instance.clone(key.getKey()))
                                       : key;
    }

    public String getFilename()
    {
        return descriptor.filenameFor(Component.DATA);
    }

    public String getIndexFilename()
    {
        return descriptor.filenameFor(Component.PRIMARY_INDEX);
    }

    public String getColumnFamilyName()
    {
        return descriptor.cfname;
    }

    public String getKeyspaceName()
    {
        return descriptor.ksname;
    }

    public List<String> getAllFilePaths()
    {
        List<String> ret = new ArrayList<>();
        for (Component component : components)
            ret.add(descriptor.filenameFor(component));
        return ret;
    }

    /**
     * Discovers existing components for the descriptor. Slow: only intended for use outside the critical path.
     */
    public static Set<Component> componentsFor(final Descriptor desc)
    {
        logger.info("Loading components for " + desc);
        try
        {
            try
            {
                return readTOC(desc);
            }
            catch (FileNotFoundException e)
            {
                Set<Component> components = discoverComponentsFor(desc);
                if (components.isEmpty())
                    return components; // sstable doesn't exist yet

                if (!components.contains(Component.TOC))
                    components.add(Component.TOC);
                appendTOC(desc, components);
                return components;
            }
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
    }

    public static Set<Component> discoverComponentsFor(Descriptor desc)
    {
        Set<Component.Type> knownTypes = Sets.difference(Component.TYPES, Collections.singleton(Component.Type.CUSTOM));
        Set<Component> components = Sets.newHashSetWithExpectedSize(knownTypes.size());
        for (Component.Type componentType : knownTypes)
        {
            if (componentType == Component.Type.DIGEST)
            {
                if (desc.digestComponent != null && new File(desc.filenameFor(desc.digestComponent)).exists())
                    components.add(desc.digestComponent);
            }
            else
            {
                Component component = new Component(componentType);
                if (new File(desc.filenameFor(component)).exists())
                    components.add(component);
            }
        }
        return components;
    }

    /** @return An estimate of the number of keys contained in the given index file. */
    protected long estimateRowsFromIndex(RandomAccessReader ifile) throws IOException
    {
        // collect sizes for the first 10000 keys, or first 10 megabytes of data
        final int SAMPLES_CAP = 10000, BYTES_CAP = (int)Math.min(10000000, ifile.length());
        int keys = 0;
        while (ifile.getFilePointer() < BYTES_CAP && keys < SAMPLES_CAP)
        {
            ByteBufferUtil.skipShortLength(ifile);
            RowIndexEntry.Serializer.skip(ifile, descriptor.version);
            keys++;
        }
        assert keys > 0 && ifile.getFilePointer() > 0 && ifile.length() > 0 : "Unexpected empty index file: " + ifile;
        long estimatedRows = ifile.length() / (ifile.getFilePointer() / keys);
        ifile.seek(0);
        return estimatedRows;
    }

    public long bytesOnDisk()
    {
        long bytes = 0;
        try
        {
            for (Component component : components) {
                bytes += HadoopFileUtils.fileSize(descriptor.filenameFor(component), descriptor.getConfiguration());
            }
        }
        catch (IOException e)
        {
           logger.error(e.getMessage());
        }

        return bytes;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "(" +
               "path='" + getFilename() + '\'' +
               ')';
    }

    private static List<String> readLines(String filename, Configuration conf) throws IOException {
        return HadoopFileUtils.readLines(filename, conf);
    }

    /**
     * Reads the list of components from the TOC component.
     * @return set of components found in the TOC
     */
    protected static Set<Component> readTOC(Descriptor descriptor) throws IOException
    {
        List<String> componentNames = readLines(descriptor.filenameFor(Component.TOC), descriptor.getConfiguration());
        Set<Component> components = Sets.newHashSetWithExpectedSize(componentNames.size());
        for (String componentName : componentNames)
        {
            logger.info("Checking for the existence of [" + componentName + "]");
            Component component = new Component(Component.Type.fromRepresentation(componentName), componentName);
            if (!HadoopFileUtils.exists(descriptor.filenameFor(component), descriptor.getConfiguration()))
                logger.error("Missing component: {}", descriptor.filenameFor(component));
            else
                components.add(component);
        }

        return components;
    }

    /**
     * Appends new component names to the TOC component.
     */
    protected static void appendTOC(Descriptor descriptor, Collection<Component> components)
    {
        String tocFile = descriptor.filenameFor(Component.TOC);

        try (BufferedWriter bufferedWriter = HadoopFileUtils.newBufferedWriter(descriptor.filenameFor(Component.TOC),
                                                                               Charsets.UTF_8,
                                                                               descriptor.getConfiguration()))
        {
            for (Component component : components)
                bufferedWriter.write(component.name + "\n");
        }
        catch (IOException e)
        {
            throw new FSWriteError(e, tocFile);
        }
    }

    /**
     * Registers new custom components. Used by custom compaction strategies.
     * Adding a component for the second time is a no-op.
     * Don't remove this - this method is a part of the public API, intended for use by custom compaction strategies.
     * @param newComponents collection of components to be added
     */
    public synchronized void addComponents(Collection<Component> newComponents)
    {
        Collection<Component> componentsToAdd = Collections2.filter(newComponents, Predicates.not(Predicates.in(components)));
        appendTOC(descriptor, componentsToAdd);
        components.addAll(componentsToAdd);
    }
}
