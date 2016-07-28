/*
 *
 *  *  Copyright 2016 Orient Technologies LTD (info(at)orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientdb.com
 *
 */
package com.orientechnologies.security.auditing;

import com.orientechnologies.common.util.OCallable;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.exception.OSchemaException;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.security.OAuditingOperation;
import com.orientechnologies.orient.server.OServer;

import java.util.concurrent.BlockingQueue;

/**
 * Thread that logs asynchronously.
 *
 * @author Luca Garulli
 */
public class OAuditingLoggingThread extends Thread {
  private final String                   databaseName;
  private final BlockingQueue<ODocument> auditingQueue;
  private volatile boolean               running        = true;
  private volatile boolean               waitForAllLogs = true;
  private OServer                        server;

  private String                         className;

  public OAuditingLoggingThread(final String iDatabaseName, final BlockingQueue auditingQueue, final OServer server) {
    super(Orient.instance().getThreadGroup(), "OrientDB Auditing Logging Thread - " + iDatabaseName);

    this.databaseName = iDatabaseName;
    this.auditingQueue = auditingQueue;
    this.server = server;
    setDaemon(true);

    // This will create a cluster in the system database for logging auditing events for "databaseName", if it doesn't already
    // exist.
    // server.getSystemDatabase().createCluster(ODefaultAuditing.AUDITING_LOG_CLASSNAME,
    // ODefaultAuditing.getClusterName(databaseName));

    className = ODefaultAuditing.getClassName(databaseName);

    server.getSystemDatabase().executeInDBScope(new OCallable<Void, ODatabase>() {
      @Override
      public Void call(ODatabase iArgument) {
        final OSchema schema = iArgument.getMetadata().getSchema();
        schema.reload();

        if (!schema.existsClass(className)) {
          OClass clazz = schema.getClass(ODefaultAuditing.AUDITING_LOG_CLASSNAME);

          try {
            OClass cls = schema.createClass(className, clazz);
            try {
              cls.createIndex(className + ".date", OClass.INDEX_TYPE.NOTUNIQUE, new String[] { "date" });
            } catch (OSchemaException e) {
              if (!e.getMessage().contains("already exists"))
                throw e;
            }
          } catch (RuntimeException e) {
            if (!e.getMessage().contains("already exists"))
              throw e;
          }

        }
        return null;
      }

    });
  }

  @Override
  public void run() {

    while (running || waitForAllLogs) {
      try {
        if (!running && auditingQueue.isEmpty()) {
          break;
        }

        final ODocument log = auditingQueue.take();

        log.setClassName(className);

        server.getSystemDatabase().save(log);

        if (server.getSecurity().getSyslog() != null) {
          byte byteOp = OAuditingOperation.UNSPECIFIED.getByte();

          if (log.containsField("operation"))
            byteOp = log.field("operation");

          String username = log.field("user");
          String message = log.field("note");
          String dbName = log.field("database");

          server.getSecurity().getSyslog().log(OAuditingOperation.getByByte(byteOp).toString(), dbName, username, message);
        }

      } catch (InterruptedException e) {
        // IGNORE AND SOFTLY EXIT

      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  public void sendShutdown(final boolean iWaitForAllLogs) {
    this.waitForAllLogs = iWaitForAllLogs;
    running = false;
    interrupt();
  }
}
