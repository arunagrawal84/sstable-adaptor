/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.sstableadaptor;


import com.netflix.sstableadaptor.sstable.SSTableSingleReader;
import com.netflix.sstableadaptor.util.SSTableUtils;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CompositeType;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Test SSTable utilites.
 */
public class TestSSTableUtils extends TestBaseSSTableFunSuite {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestSSTableUtils.class);

    /**
     * Setting up resources prior to running any tests.
     * @throws Exception when we cannot initialize the resources
     */
    @BeforeClass
    public static void setup() throws Exception {
        LOGGER.info("Running TestSSTableUtils setup ...");
        TestBaseSSTableFunSuite.setup();
    }

    /**
     * Tear down resources after all tests.
     * @throws Exception when teardown has an issue
     */
    @AfterClass
    public static void teardown() throws Exception {
        LOGGER.info("Tearing TestSSTableUtils down ...");
        TestBaseSSTableFunSuite.teardown();
    }

    /**
     * Test on parsing out a ByteBuffer to a list of strings.
     * @throws IOException
     */
    @Test
    public void testParsingCompositeKey() throws IOException {
        final String inputSSTableFullPathFileName = CASS3_DATA_DIR + "keyspace1/compressed_bills/mc-2-big-Data.db";

        final SSTableSingleReader SSTableSingleReader =
                                new SSTableSingleReader(inputSSTableFullPathFileName,
                                                        TestBaseSSTableFunSuite.HADOOP_CONF);

        final CFMetaData cfMetaData = SSTableSingleReader.getCfMetaData();
        final String user = "user2";
        final String email = "abc@netflix.com";
        final AbstractType<?> keyDataType = cfMetaData.getKeyValidator();

        Assert.assertTrue(keyDataType instanceof CompositeType);
        final ByteBuffer keyInByteBuffer = ((CompositeType) keyDataType).decompose(user, email);

        final List<Object> objects = SSTableUtils.parsePrimaryKey(cfMetaData, keyInByteBuffer);

        Assert.assertEquals(2, objects.size());
        Assert.assertEquals(user, objects.get(0));
        Assert.assertEquals(email, objects.get(1));
    }

}
