/**
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

package org.apache.hadoop.hive.ql.metadata.formatting;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.apache.hive.common.util.HiveStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.WMResourcePlan;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.metadata.ForeignKeyInfo;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.NotNullConstraint;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.PrimaryKeyInfo;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.metadata.UniqueConstraint;
import org.apache.hadoop.hive.ql.session.SessionState;

/**
 * Format table and index information for human readability using
 * simple lines of text.
 */
class TextMetaDataFormatter implements MetaDataFormatter {
  private static final Logger LOG = LoggerFactory.getLogger(TextMetaDataFormatter.class);

  private static final int separator = Utilities.tabCode;
  private static final int terminator = Utilities.newLineCode;

  /** The number of columns to be used in pretty formatting metadata output.
   * If -1, then the current terminal width is auto-detected and used.
   */
  private final int prettyOutputNumCols;
  private final boolean showPartColsSeparately;

  public TextMetaDataFormatter(int prettyOutputNumCols, boolean partColsSeparately) {
    this.prettyOutputNumCols = prettyOutputNumCols;
    this.showPartColsSeparately = partColsSeparately;
  }

  /**
   * Write an error message.
   */
  @Override
  public void error(OutputStream out, String msg, int errorCode, String sqlState)
      throws HiveException
      {
    error(out, msg, errorCode, sqlState, null);
      }

  @Override
  public void error(OutputStream out, String errorMessage, int errorCode, String sqlState, String errorDetail)
      throws HiveException
      {
    try {
      out.write(errorMessage.getBytes("UTF-8"));
      if(errorDetail != null) {
        out.write(errorDetail.getBytes("UTF-8"));
      }
      out.write(errorCode);
      if(sqlState != null) {
        out.write(sqlState.getBytes("UTF-8"));//this breaks all the tests in .q files
      }
      out.write(terminator);
    } catch (Exception e) {
      throw new HiveException(e);
    }
      }
  /**
   * Show a list of tables.
   */
  @Override
  public void showTables(DataOutputStream out, Set<String> tables)
      throws HiveException
      {
    Iterator<String> iterTbls = tables.iterator();

    try {
      while (iterTbls.hasNext()) {
        // create a row per table name
        out.write(iterTbls.next().getBytes("UTF-8"));
        out.write(terminator);
      }
    } catch (IOException e) {
      throw new HiveException(e);
    }
      }

  @Override
  public void describeTable(DataOutputStream outStream,  String colPath,
      String tableName, Table tbl, Partition part, List<FieldSchema> cols,
      boolean isFormatted, boolean isExt,
      boolean isOutputPadded, List<ColumnStatisticsObj> colStats,
      PrimaryKeyInfo pkInfo, ForeignKeyInfo fkInfo,
      UniqueConstraint ukInfo, NotNullConstraint nnInfo) throws HiveException {
    try {
      List<FieldSchema> partCols = tbl.isPartitioned() ? tbl.getPartCols() : null;
      String output = "";

      boolean isColStatsAvailable = colStats != null;

      TextMetaDataTable mdt = new TextMetaDataTable();
      if (isFormatted && !isColStatsAvailable) {
        output = "# ";
      }
      if (isFormatted) {
        mdt.addRow(MetaDataFormatUtils.getColumnsHeader(colStats));
      }
      for (FieldSchema col : cols) {
        mdt.addRow(MetaDataFormatUtils.extractColumnValues(col, isColStatsAvailable,
            MetaDataFormatUtils.getColumnStatisticsObject(col.getName(), col.getType(), colStats)));
      }
      if (isColStatsAvailable) {
        mdt.transpose();
      }
      output += mdt.renderTable(isOutputPadded);

      if (colPath.equals(tableName)) {
        if ((partCols != null) && !partCols.isEmpty() && showPartColsSeparately) {

          mdt = new TextMetaDataTable();
          output += MetaDataFormatUtils.LINE_DELIM + "# Partition Information" + MetaDataFormatUtils.LINE_DELIM + "# ";
          mdt.addRow(MetaDataFormatUtils.getColumnsHeader(null));
          for (FieldSchema col : partCols) {
            mdt.addRow(MetaDataFormatUtils.extractColumnValues(col));
          }
          output += mdt.renderTable(isOutputPadded);
        }
      } else {

        String statsState;
        if (tbl.getParameters() != null && (statsState = tbl.getParameters().get(StatsSetupConst.COLUMN_STATS_ACCURATE)) != null) {
          StringBuilder str = new StringBuilder();
          MetaDataFormatUtils.formatOutput(StatsSetupConst.COLUMN_STATS_ACCURATE,
              isFormatted ? StringEscapeUtils.escapeJava(statsState) : HiveStringUtils.escapeJava(statsState),
              str, isOutputPadded);
          output = output.concat(str.toString());
        }
      }
      outStream.write(output.getBytes("UTF-8"));

      if (tableName.equals(colPath)) {
        if (isFormatted) {
          if (part != null) {
            output = MetaDataFormatUtils.getPartitionInformation(part);
          } else {
            output = MetaDataFormatUtils.getTableInformation(tbl, isOutputPadded);
          }
          outStream.write(output.getBytes("UTF-8"));

          if ((pkInfo != null && !pkInfo.getColNames().isEmpty()) ||
              (fkInfo != null && !fkInfo.getForeignKeys().isEmpty()) ||
              (ukInfo != null && !ukInfo.getUniqueConstraints().isEmpty()) ||
              (nnInfo != null && !nnInfo.getNotNullConstraints().isEmpty())) {
            output = MetaDataFormatUtils.getConstraintsInformation(pkInfo, fkInfo, ukInfo, nnInfo);
            outStream.write(output.getBytes("UTF-8"));
          }
        }

        // if extended desc table then show the complete details of the table
        if (isExt) {
          // add empty line
          outStream.write(terminator);
          if (part != null) {
            // show partition information
            outStream.write(("Detailed Partition Information").getBytes("UTF-8"));
            outStream.write(separator);
            outStream.write(part.getTPartition().toString().getBytes("UTF-8"));
            outStream.write(separator);
            // comment column is empty
            outStream.write(terminator);
          } else {
            // show table information
            outStream.write(("Detailed Table Information").getBytes("UTF-8"));
            outStream.write(separator);
            outStream.write(tbl.getTTable().toString().getBytes("UTF-8"));
            outStream.write(separator);
            outStream.write(terminator);
          }
          if ((pkInfo != null && !pkInfo.getColNames().isEmpty()) ||
              (fkInfo != null && !fkInfo.getForeignKeys().isEmpty()) ||
              (ukInfo != null && !ukInfo.getUniqueConstraints().isEmpty()) ||
              (nnInfo != null && !nnInfo.getNotNullConstraints().isEmpty())) {
            outStream.write(("Constraints").getBytes("UTF-8"));
            outStream.write(separator);
            if (pkInfo != null && !pkInfo.getColNames().isEmpty()) {
              outStream.write(pkInfo.toString().getBytes("UTF-8"));
              outStream.write(terminator);
            }
            if (fkInfo != null && !fkInfo.getForeignKeys().isEmpty()) {
              outStream.write(fkInfo.toString().getBytes("UTF-8"));
              outStream.write(terminator);
            }
            if (ukInfo != null && !ukInfo.getUniqueConstraints().isEmpty()) {
              outStream.write(ukInfo.toString().getBytes("UTF-8"));
              outStream.write(terminator);
            }
            if (nnInfo != null && !nnInfo.getNotNullConstraints().isEmpty()) {
              outStream.write(nnInfo.toString().getBytes("UTF-8"));
              outStream.write(terminator);
            }
          }
        }
      }
    } catch (IOException e) {
      throw new HiveException(e);
    }
  }

  @Override
  public void showTableStatus(DataOutputStream outStream,
      Hive db,
      HiveConf conf,
      List<Table> tbls,
      Map<String, String> part,
      Partition par)
          throws HiveException
          {
    try {
      Iterator<Table> iterTables = tbls.iterator();
      while (iterTables.hasNext()) {
        // create a row per table name
        Table tbl = iterTables.next();
        String tableName = tbl.getTableName();
        String tblLoc = null;
        String inputFormattCls = null;
        String outputFormattCls = null;
        if (part != null) {
          if (par != null) {
            if (par.getLocation() != null) {
              tblLoc = par.getDataLocation().toString();
            }
            inputFormattCls = par.getInputFormatClass().getName();
            outputFormattCls = par.getOutputFormatClass().getName();
          }
        } else {
          if (tbl.getPath() != null) {
            tblLoc = tbl.getDataLocation().toString();
          }
          inputFormattCls = tbl.getInputFormatClass().getName();
          outputFormattCls = tbl.getOutputFormatClass().getName();
        }

        String owner = tbl.getOwner();
        List<FieldSchema> cols = tbl.getCols();
        String ddlCols = MetaStoreUtils.getDDLFromFieldSchema("columns", cols);
        boolean isPartitioned = tbl.isPartitioned();
        String partitionCols = "";
        if (isPartitioned) {
          partitionCols = MetaStoreUtils.getDDLFromFieldSchema(
              "partition_columns", tbl.getPartCols());
        }

        outStream.write(("tableName:" + tableName).getBytes("UTF-8"));
        outStream.write(terminator);
        outStream.write(("owner:" + owner).getBytes("UTF-8"));
        outStream.write(terminator);
        outStream.write(("location:" + tblLoc).getBytes("UTF-8"));
        outStream.write(terminator);
        outStream.write(("inputformat:" + inputFormattCls).getBytes("UTF-8"));
        outStream.write(terminator);
        outStream.write(("outputformat:" + outputFormattCls).getBytes("UTF-8"));
        outStream.write(terminator);
        outStream.write(("columns:" + ddlCols).getBytes("UTF-8"));
        outStream.write(terminator);
        outStream.write(("partitioned:" + isPartitioned).getBytes("UTF-8"));
        outStream.write(terminator);
        outStream.write(("partitionColumns:" + partitionCols).getBytes("UTF-8"));
        outStream.write(terminator);
        // output file system information
        Path tblPath = tbl.getPath();
        List<Path> locations = new ArrayList<Path>();
        if (isPartitioned) {
          if (par == null) {
            for (Partition curPart : db.getPartitions(tbl)) {
              if (curPart.getLocation() != null) {
                locations.add(new Path(curPart.getLocation()));
              }
            }
          } else {
            if (par.getLocation() != null) {
              locations.add(new Path(par.getLocation()));
            }
          }
        } else {
          if (tblPath != null) {
            locations.add(tblPath);
          }
        }
        if (!locations.isEmpty()) {
          writeFileSystemStats(outStream, conf, locations, tblPath, false, 0);
        }

        outStream.write(terminator);
      }
    } catch (IOException e) {
      throw new HiveException(e);
    }
  }

  private static class FileData {
    public long totalFileSize = 0;
    public long maxFileSize = 0;
    public long minFileSize = Long.MAX_VALUE;
    public long lastAccessTime = 0;
    public long lastUpdateTime = 0;
    public int numOfFiles = 0;
  }

  // TODO: why is this in text formatter?!!
  //       This computes stats and should be in stats (de-duplicated too).
  private void writeFileSystemStats(DataOutputStream outStream,
      HiveConf conf,
      List<Path> locations,
      Path tblPath, boolean partSpecified, int indent) throws IOException {
    FileData fd = new FileData();
    boolean unknown = false;
    FileSystem fs = tblPath.getFileSystem(conf);
    // in case all files in locations do not exist
    try {
      FileStatus tmpStatus = fs.getFileStatus(tblPath);
      fd.lastAccessTime = tmpStatus.getAccessTime();
      fd.lastUpdateTime = tmpStatus.getModificationTime();
      if (partSpecified) {
        // check whether the part exists or not in fs
        tmpStatus = fs.getFileStatus(locations.get(0));
      }
    } catch (IOException e) {
      LOG.warn(
          "Cannot access File System. File System status will be unknown: ", e);
      unknown = true;
    }

    if (!unknown) {
      for (Path loc : locations) {
        try {
          FileStatus status = fs.getFileStatus(loc);
          // no matter loc is the table location or part location, it must be a
          // directory.
          if (!status.isDirectory()) {
            continue;
          }
          processDir(status, fs, fd);
        } catch (IOException e) {
          // ignore
        }
      }
    }
    String unknownString = "unknown";

    for (int k = 0; k < indent; k++) {
      outStream.write(Utilities.INDENT.getBytes("UTF-8"));
    }
    outStream.write("totalNumberFiles:".getBytes("UTF-8"));
    outStream.write((unknown ? unknownString : "" + fd.numOfFiles).getBytes("UTF-8"));
    outStream.write(terminator);

    for (int k = 0; k < indent; k++) {
      outStream.write(Utilities.INDENT.getBytes("UTF-8"));
    }
    outStream.write("totalFileSize:".getBytes("UTF-8"));
    outStream.write((unknown ? unknownString : "" + fd.totalFileSize).getBytes("UTF-8"));
    outStream.write(terminator);

    for (int k = 0; k < indent; k++) {
      outStream.write(Utilities.INDENT.getBytes("UTF-8"));
    }
    outStream.write("maxFileSize:".getBytes("UTF-8"));
    outStream.write((unknown ? unknownString : "" + fd.maxFileSize).getBytes("UTF-8"));
    outStream.write(terminator);

    for (int k = 0; k < indent; k++) {
      outStream.write(Utilities.INDENT.getBytes("UTF-8"));
    }
    outStream.write("minFileSize:".getBytes("UTF-8"));
    if (fd.numOfFiles > 0) {
      outStream.write((unknown ? unknownString : "" + fd.minFileSize).getBytes("UTF-8"));
    } else {
      outStream.write((unknown ? unknownString : "" + 0).getBytes("UTF-8"));
    }
    outStream.write(terminator);

    for (int k = 0; k < indent; k++) {
      outStream.write(Utilities.INDENT.getBytes("UTF-8"));
    }
    outStream.write("lastAccessTime:".getBytes("UTF-8"));
    outStream.writeBytes((unknown || fd.lastAccessTime < 0) ? unknownString : ""
        + fd.lastAccessTime);
    outStream.write(terminator);

    for (int k = 0; k < indent; k++) {
      outStream.write(Utilities.INDENT.getBytes("UTF-8"));
    }
    outStream.write("lastUpdateTime:".getBytes("UTF-8"));
    outStream.write((unknown ? unknownString : "" + fd.lastUpdateTime).getBytes("UTF-8"));
    outStream.write(terminator);
  }

  private void processDir(FileStatus status, FileSystem fs, FileData fd) throws IOException {
    long accessTime = status.getAccessTime();
    long updateTime = status.getModificationTime();
    if (accessTime > fd.lastAccessTime) {
      fd.lastAccessTime = accessTime;
    }
    if (updateTime > fd.lastUpdateTime) {
      fd.lastUpdateTime = updateTime;
    }
    FileStatus[] files = fs.listStatus(status.getPath());
    for (FileStatus currentStatus : files) {
      if (currentStatus.isDirectory()) {
        processDir(currentStatus, fs, fd);
        continue;
      }
      fd.numOfFiles++;
      long fileLen = currentStatus.getLen();
      fd.totalFileSize += fileLen;
      if (fileLen > fd.maxFileSize) {
        fd.maxFileSize = fileLen;
      }
      if (fileLen < fd.minFileSize) {
        fd.minFileSize = fileLen;
      }
      accessTime = currentStatus.getAccessTime();
      updateTime = currentStatus.getModificationTime();
      if (accessTime > fd.lastAccessTime) {
        fd.lastAccessTime = accessTime;
      }
      if (updateTime > fd.lastUpdateTime) {
        fd.lastUpdateTime = updateTime;
      }
    }
  }

  /**
   * Show the table partitions.
   */
  @Override
  public void showTablePartitions(DataOutputStream outStream, List<String> parts)
      throws HiveException
      {
    try {
      for (String part : parts) {
        // Partition names are URL encoded. We decode the names unless Hive
        // is configured to use the encoded names.
        SessionState ss = SessionState.get();
        if (ss != null && ss.getConf() != null &&
            !ss.getConf().getBoolVar(HiveConf.ConfVars.HIVE_DECODE_PARTITION_NAME)) {
          outStream.write(part.getBytes("UTF-8"));
        } else {
          outStream.write(FileUtils.unescapePathName(part).getBytes("UTF-8"));
        }
        outStream.write(terminator);
      }
    } catch (IOException e) {
      throw new HiveException(e);
    }
      }

  /**
   * Show the list of databases
   */
  @Override
  public void showDatabases(DataOutputStream outStream, List<String> databases)
      throws HiveException
      {
    try {
      for (String database : databases) {
        // create a row per database name
        outStream.write(database.getBytes("UTF-8"));
        outStream.write(terminator);
      }
    } catch (IOException e) {
      throw new HiveException(e);
    }
      }

  /**
   * Describe a database
   */
  @Override
  public void showDatabaseDescription(DataOutputStream outStream, String database, String comment,
      String location, String ownerName, String ownerType, Map<String, String> params)
          throws HiveException {
    try {
      outStream.write(database.getBytes("UTF-8"));
      outStream.write(separator);
      if (comment != null) {
        outStream.write(HiveStringUtils.escapeJava(comment).getBytes("UTF-8"));
      }
      outStream.write(separator);
      if (location != null) {
        outStream.write(location.getBytes("UTF-8"));
      }
      outStream.write(separator);
      if (ownerName != null) {
        outStream.write(ownerName.getBytes("UTF-8"));
      }
      outStream.write(separator);
      if (ownerType != null) {
        outStream.write(ownerType.getBytes("UTF-8"));
      }
      outStream.write(separator);
      if (params != null && !params.isEmpty()) {
        outStream.write(params.toString().getBytes("UTF-8"));
      }
      outStream.write(terminator);
    } catch (IOException e) {
      throw new HiveException(e);
    }
  }

  public void showResourcePlans(DataOutputStream out, List<WMResourcePlan> resourcePlans)
      throws HiveException {
    try {
      for (WMResourcePlan plan : resourcePlans) {
        out.write(plan.getName().getBytes("UTF-8"));
        out.write(separator);
        out.write(plan.getStatus().name().getBytes("UTF-8"));
        out.write(separator);
        if (plan.isSetQueryParallelism()) {
          out.writeBytes(Integer.toString(plan.getQueryParallelism()));
        } else {
          out.writeBytes("null");
        }
        out.write(separator);
        if (plan.isSetDefaultPoolPath()) {
          out.write(plan.getDefaultPoolPath().getBytes("UTF-8"));
        } else {
          out.writeBytes("null");
        }
        out.write(terminator);
      }
    } catch (IOException e) {
      throw new HiveException(e);
    }
  }

  public void showErrors(DataOutputStream out, List<String> errors) throws HiveException {
    try {
      for (String error : errors) {
        out.write(error.getBytes("UTF-8"));
        out.write(terminator);
      }
    } catch (IOException e) {
      throw new HiveException(e);
    }
  }
}
