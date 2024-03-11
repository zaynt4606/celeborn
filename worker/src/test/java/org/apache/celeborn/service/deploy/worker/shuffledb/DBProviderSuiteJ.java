/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.service.deploy.worker.shuffledb;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.io.File;
import java.io.IOException;

import org.apache.commons.lang3.SystemUtils;
import org.junit.Test;

import org.apache.celeborn.common.util.JavaUtils;
import org.apache.celeborn.common.util.Utils;

public class DBProviderSuiteJ {

  @Test
  public void testRockDBCheckVersionFailed() throws IOException {
    testCheckVersionFailed(DBBackend.ROCKSDB, "rocksdb");
  }

  @Test
  public void testLevelDBCheckVersionFailed() throws IOException {
    assumeFalse(SystemUtils.IS_OS_MAC_OSX && SystemUtils.OS_ARCH.equals("aarch64"));
    testCheckVersionFailed(DBBackend.LEVELDB, "leveldb");
  }

  private void testCheckVersionFailed(DBBackend dbBackend, String namePrefix) throws IOException {
    String root = System.getProperty("java.io.tmpdir");
    File dbFile = Utils.createDirectory(root, namePrefix);
    try {
      StoreVersion v1 = new StoreVersion(1, 0);
      DBProvider.initDB(dbBackend, dbFile, v1).close();
      StoreVersion v2 = new StoreVersion(2, 0);
      IOException ioe =
          assertThrows(IOException.class, () -> DBProvider.initDB(dbBackend, dbFile, v2));
      assertTrue(ioe.getMessage().contains("incompatible with current version StoreVersion[2.0]"));
    } finally {
      JavaUtils.deleteRecursively(dbFile);
    }
  }
}
