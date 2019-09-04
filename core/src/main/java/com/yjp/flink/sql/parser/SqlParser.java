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


package com.yjp.flink.sql.parser;

import com.yjp.flink.sql.enums.ETableType;
import com.yjp.flink.sql.table.TableInfo;
import com.yjp.flink.sql.table.TableInfoParser;
import com.yjp.flink.sql.util.YjpStringUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.shaded.curator.org.apache.curator.shaded.com.google.common.collect.Lists;
import org.apache.flink.shaded.guava18.com.google.common.base.Strings;

import java.util.List;
import java.util.Set;

/**
 * Reason:
 * Date: 2018/6/22
 * Company: www.yjp.com
 *
 * @author xuchao
 */

public class SqlParser {

    private static final char SQL_DELIMITER = ';';

    private static String LOCAL_SQL_PLUGIN_ROOT;

    private static List<IParser> sqlParserList = Lists.newArrayList(CreateFuncParser.newInstance(),
            CreateTableParser.newInstance(), InsertSqlParser.newInstance(), CreateTmpTableParser.newInstance());

    public static void setLocalSqlPluginRoot(String localSqlPluginRoot) {
        LOCAL_SQL_PLUGIN_ROOT = localSqlPluginRoot;
    }

    /**
     * flink support sql syntax
     * CREATE TABLE sls_stream() with ();
     * CREATE (TABLE|SCALA) FUNCTION fcnName WITH com.yjp.com;
     * insert into tb1 select * from tb2;
     *
     * @param sql
     */
    public static SqlTree parseSql(String sql) throws Exception {

        if (StringUtils.isBlank(sql)) {
            throw new RuntimeException("sql is not null");
        }

        if (LOCAL_SQL_PLUGIN_ROOT == null) {
            throw new RuntimeException("need to set local sql plugin root");
        }

        sql = sql.replaceAll("--.*", "")
                .replaceAll("\r\n", " ")
                .replaceAll("\n", " ")
                .replace("\t", " ").trim();
        //通过;切割sql文件
        List<String> sqlArr = YjpStringUtil.splitIgnoreQuota(sql, SQL_DELIMITER);
        SqlTree sqlTree = new SqlTree();
        TableInfoParser tableInfoParser = new TableInfoParser();
        for (String childSql : sqlArr) {
            if (Strings.isNullOrEmpty(childSql)) {
                continue;
            }
            boolean result = false;
            for (IParser sqlParser : sqlParserList) {
                //通过不同的正则表达式判断不同类型 创建表 创建函数  创建临时表 insert
                if (!sqlParser.verify(childSql)) {
                    continue;
                }
                // 将不同类型的sql 转换为不同的成员变量 放入sqlTree
                sqlParser.parseSql(childSql, sqlTree);
                result = true;
            }

            if (!result) {
                throw new RuntimeException(String.format("%s:Syntax does not support,the format of SQL like insert into tb1 select * from tb2.", childSql));
            }
        }

        //解析exec-sql  没有insert into 说明sql没有执行语句
        if (sqlTree.getExecSqlList().size() == 0) {
            throw new RuntimeException("sql no executable statement");
        }

        for (InsertSqlParser.SqlParseResult result : sqlTree.getExecSqlList()) {
            List<String> sourceTableList = result.getSourceTableList();
            List<String> targetTableList = result.getTargetTableList();
            Set<String> tmpTableList = sqlTree.getTmpTableMap().keySet();

            for (String tableName : sourceTableList) {
                if (!tmpTableList.contains(tableName)) {
                    CreateTableParser.SqlParserResult createTableResult = sqlTree.getPreDealTableMap().get(tableName);
                    if (createTableResult == null) {
                        throw new RuntimeException("can't find table " + tableName);
                    }
                    //为tableInfo赋值 通过反射new出不同的子类对象
                    TableInfo tableInfo = tableInfoParser.parseWithTableType(ETableType.SOURCE.getType(),
                            createTableResult, LOCAL_SQL_PLUGIN_ROOT);
                    sqlTree.addTableInfo(tableName, tableInfo);
                }
            }

            for (String tableName : targetTableList) {
                if (!tmpTableList.contains(tableName)) {
                    CreateTableParser.SqlParserResult createTableResult = sqlTree.getPreDealTableMap().get(tableName);
                    if (createTableResult == null) {
                        throw new RuntimeException("can't find table " + tableName);
                    }
                    //为tableInfo赋值 通过反射new出不同的子类对象
                    TableInfo tableInfo = tableInfoParser.parseWithTableType(ETableType.SINK.getType(),
                            createTableResult, LOCAL_SQL_PLUGIN_ROOT);
                    sqlTree.addTableInfo(tableName, tableInfo);
                }
            }
        }

        for (CreateTmpTableParser.SqlParserResult result : sqlTree.getTmpSqlList()) {
            List<String> sourceTableList = result.getSourceTableList();
            for (String tableName : sourceTableList) {
                if (!sqlTree.getTableInfoMap().keySet().contains(tableName)) {
                    CreateTableParser.SqlParserResult createTableResult = sqlTree.getPreDealTableMap().get(tableName);
                    if (createTableResult == null) {
                        CreateTmpTableParser.SqlParserResult tmpTableResult = sqlTree.getTmpTableMap().get(tableName);
                        if (tmpTableResult == null) {
                            throw new RuntimeException("can't find table " + tableName);
                        }
                    } else {
                        TableInfo tableInfo = tableInfoParser.parseWithTableType(ETableType.SOURCE.getType(),
                                createTableResult, LOCAL_SQL_PLUGIN_ROOT);
                        sqlTree.addTableInfo(tableName, tableInfo);
                    }
                }
            }
        }

        return sqlTree;
    }
}
