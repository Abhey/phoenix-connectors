/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.spark;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.phoenix.end2end.BaseOrderByIT;
import org.apache.phoenix.spark.sql.connector.PhoenixDataSource;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryBuilder;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.junit.Test;
import org.junit.Ignore;



import scala.Option;
import scala.collection.JavaConverters;

public class OrderByIT extends BaseOrderByIT {

    @Override
    protected ResultSet executeQueryThrowsException(Connection conn, QueryBuilder queryBuilder,
                                                    String expectedPhoenixExceptionMsg, String expectedSparkExceptionMsg) {
        ResultSet rs = null;
        try {
            rs = executeQuery(conn, queryBuilder);
            fail();
        }
        catch(Exception e) {
            assertTrue(e.getMessage().contains(expectedSparkExceptionMsg));
        }
        return rs;
    }

    @Override
    protected ResultSet executeQuery(Connection conn, QueryBuilder queryBuilder) throws SQLException {
        return SparkUtil.executeQuery(conn, queryBuilder, getUrl(), config);
    }

    @Test
    public void testOrderByWithJoin() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
            conn.setAutoCommit(false);
            String tableName1 = generateUniqueName();
            String ddl = "CREATE TABLE " + tableName1 +
                    "  (a_string varchar not null, cf1.a integer, cf1.b varchar, col1 integer, cf2.c varchar, cf2.d integer " +
                    "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
            createTestTable(getUrl(), ddl);
            String dml = "UPSERT INTO " + tableName1 + " VALUES(?,?,?,?,?,?)";
            PreparedStatement stmt = conn.prepareStatement(dml);
            stmt.setString(1, "a");
            stmt.setInt(2, 40);
            stmt.setString(3, "aa");
            stmt.setInt(4, 10);
            stmt.setString(5, "bb");
            stmt.setInt(6, 20);
            stmt.execute();
            stmt.setString(1, "c");
            stmt.setInt(2, 30);
            stmt.setString(3, "cc");
            stmt.setInt(4, 50);
            stmt.setString(5, "dd");
            stmt.setInt(6, 60);
            stmt.execute();
            stmt.setString(1, "b");
            stmt.setInt(2, 40);
            stmt.setString(3, "bb");
            stmt.setInt(4, 5);
            stmt.setString(5, "aa");
            stmt.setInt(6, 80);
            stmt.execute();
            conn.commit();

            String tableName2 = generateUniqueName();
            ddl = "CREATE TABLE " + tableName2 +
                    "  (a_string varchar not null, col1 integer" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
            createTestTable(getUrl(), ddl);

            dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
            stmt = conn.prepareStatement(dml);
            stmt.setString(1, "a");
            stmt.setInt(2, 40);
            stmt.execute();
            stmt.setString(1, "b");
            stmt.setInt(2, 20);
            stmt.execute();
            stmt.setString(1, "c");
            stmt.setInt(2, 30);
            stmt.execute();
            conn.commit();

            // create two PhoenixRDDs  using the table names and columns that are required for the JOIN query
            List<String> table1Columns = new ArrayList(
                Arrays.asList("A_STRING", "CF1.A", "CF1.B", "COL1", "CF2.C", "CF2.D"));
            SQLContext sqlContext = SparkUtil.getSparkSession().sqlContext();
            Dataset phoenixDataSet = SparkUtil.getSparkSession().read().format("phoenix")
                    .option("table", tableName1)
                    .option(PhoenixDataSource.ZOOKEEPER_URL, getUrl()).load();
            phoenixDataSet.createOrReplaceTempView(tableName1);
            phoenixDataSet = SparkUtil.getSparkSession().read().format("phoenix")
                    .option("table", tableName2)
                    .option(PhoenixDataSource.ZOOKEEPER_URL, getUrl()).load();
            phoenixDataSet.createOrReplaceTempView(tableName2);

            String query =
                    "SELECT T1.* FROM " + tableName1 + " T1 JOIN " + tableName2
                            + " T2 ON T1.A_STRING = T2.A_STRING ORDER BY T1.`CF1.B`";
            Dataset<Row> dataset =
                    sqlContext.sql(query);
            List<Row> rows = dataset.collectAsList();
            ResultSet rs = new SparkResultSet(rows, dataset.columns());

            assertTrue(rs.next());
            assertEquals("a",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertEquals("aa",rs.getString(3));
            assertEquals(10,rs.getInt(4));
            assertEquals("bb",rs.getString(5));
            assertEquals(20,rs.getInt(6));
            assertTrue(rs.next());
            assertEquals("b",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertEquals("bb",rs.getString(3));
            assertEquals(5,rs.getInt(4));
            assertEquals("aa",rs.getString(5));
            assertEquals(80,rs.getInt(6));
            assertTrue(rs.next());
            assertEquals("c",rs.getString(1));
            assertEquals(30,rs.getInt(2));
            assertEquals("cc",rs.getString(3));
            assertEquals(50,rs.getInt(4));
            assertEquals("dd",rs.getString(5));
            assertEquals(60,rs.getInt(6));
            assertFalse(rs.next());

            query =
                    "SELECT T1.A_STRING, T2.COL1 FROM " + tableName1 + " T1 JOIN " + tableName2
                            + " T2 ON T1.A_STRING = T2.A_STRING ORDER BY T2.COL1";
            dataset =  sqlContext.sql(query);
            rows = dataset.collectAsList();
            rs = new SparkResultSet(rows, dataset.columns());
            assertTrue(rs.next());
            assertEquals("b",rs.getString(1));
            assertEquals(20,rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("c",rs.getString(1));
            assertEquals(30,rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("a",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertFalse(rs.next());
        }
    }


    @Test
    public void testOrderByWithUnionAll() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn = DriverManager.getConnection(getUrl(), props)){
            conn.setAutoCommit(false);
            String tableName1 = generateUniqueName();
            String ddl = "CREATE TABLE  " + tableName1 +
                    "  (a_string varchar not null, cf1.a integer, cf1.b varchar, col1 integer, cf2.c varchar, cf2.d integer " +
                    "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
            createTestTable(getUrl(), ddl);
            String dml = "UPSERT INTO " + tableName1 + " VALUES(?,?,?,?,?,?)";
            PreparedStatement stmt = conn.prepareStatement(dml);
            stmt.setString(1, "a");
            stmt.setInt(2, 40);
            stmt.setString(3, "aa");
            stmt.setInt(4, 10);
            stmt.setString(5, "bb");
            stmt.setInt(6, 20);
            stmt.execute();
            stmt.setString(1, "c");
            stmt.setInt(2, 30);
            stmt.setString(3, "cc");
            stmt.setInt(4, 50);
            stmt.setString(5, "dd");
            stmt.setInt(6, 60);
            stmt.execute();
            stmt.setString(1, "b");
            stmt.setInt(2, 40);
            stmt.setString(3, "bb");
            stmt.setInt(4, 5);
            stmt.setString(5, "aa");
            stmt.setInt(6, 80);
            stmt.execute();
            conn.commit();

            String tableName2 = generateUniqueName();
            ddl = "CREATE TABLE " + tableName2 +
                    "  (a_string varchar not null, col1 integer" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
            createTestTable(getUrl(), ddl);

            dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
            stmt = conn.prepareStatement(dml);
            stmt.setString(1, "aa");
            stmt.setInt(2, 40);
            stmt.execute();
            stmt.setString(1, "bb");
            stmt.setInt(2, 10);
            stmt.execute();
            stmt.setString(1, "cc");
            stmt.setInt(2, 30);
            stmt.execute();
            conn.commit();


            SQLContext sqlContext = SparkUtil.getSparkSession().sqlContext();
            Dataset phoenixDataSet = SparkUtil.getSparkSession().read().format("phoenix")
                            .option("table", tableName1)
                            .option(PhoenixDataSource.ZOOKEEPER_URL, getUrl()).load();
            phoenixDataSet.createOrReplaceTempView(tableName1);
            phoenixDataSet = SparkUtil.getSparkSession().read().format("phoenix")
                    .option("table", tableName2)
                    .option(PhoenixDataSource.ZOOKEEPER_URL, getUrl()).load();
            phoenixDataSet.createOrReplaceTempView(tableName2);

            String query =
                    "select a_string, `cf2.d` from " + tableName1 + " union all select * from "
                            + tableName2 + " order by `cf2.d`";
            Dataset<Row> dataset =
                    sqlContext.sql(query);
            List<Row> rows = dataset.collectAsList();
            ResultSet rs = new SparkResultSet(rows, dataset.columns());
            assertTrue(rs.next());
            assertEquals("bb",rs.getString(1));
            assertEquals(10,rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("a",rs.getString(1));
            assertEquals(20,rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("cc",rs.getString(1));
            assertEquals(30,rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("aa",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("c",rs.getString(1));
            assertEquals(60,rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("b",rs.getString(1));
            assertEquals(80,rs.getInt(2));
            assertFalse(rs.next());
        }
    }

    @Test
    public void testCombinationOfOrAndFilters() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
            conn.setAutoCommit(false);
            String tableName1 = generateUniqueName();
            String ddl = "CREATE TABLE  " + tableName1 +
                    "  (ENTITY_INSTANCE_ID BIGINT NOT NULL, MEMBER_ID VARCHAR(50) NOT NULL, CASE_ID VARCHAR(250) NOT NULL," +
                    " CANCELLATION_FLAG VARCHAR(1), CASE_MATCH_TYPE CHAR(1), CONSTRAINT PK1 PRIMARY KEY(ENTITY_INSTANCE_ID, " +
                    "MEMBER_ID, CASE_ID))\n";
            createTestTable(getUrl(), ddl);
            SQLContext sqlContext = SparkUtil.getSparkSession().sqlContext();
            Dataset phoenixDataSet = SparkUtil.getSparkSession().read().format("phoenix")
                    .option("table", tableName1)
                    .option(PhoenixDataSource.ZOOKEEPER_URL, getUrl()).load();
            phoenixDataSet.createOrReplaceTempView(tableName1);
            String dml = "UPSERT INTO " + tableName1 + " VALUES(?,?,?,?,?)";
            PreparedStatement stmt = conn.prepareStatement(dml);
            stmt.setInt(1, 40);
            stmt.setString(2, "a");
            stmt.setString(3, "b");
            stmt.setString(4, "Y");
            stmt.setString(5, "M");
            stmt.execute();
            stmt.setInt(1, 40);
            stmt.setString(2, "c");
            stmt.setString(3, "d");
            stmt.setNull(4, Types.VARCHAR);
            stmt.setString(5, "M");
            stmt.execute();
            stmt.setInt(1, 40);
            stmt.setString(2, "e");
            stmt.setString(3, "f");
            stmt.setString(4, "N");
            stmt.setString(5, "M");
            stmt.execute();
            stmt.setInt(1, 41);
            stmt.setString(2, "f");
            stmt.setString(3, "g");
            stmt.setString(4, "N");
            stmt.setString(5, "C");
            stmt.execute();
            conn.commit();
            String query =
                    "select count(*) from " + tableName1 + " where ENTITY_INSTANCE_ID = 40 and  " +
                            "(CANCELLATION_FLAG <> 'Y' OR CANCELLATION_FLAG IS NULL ) and CASE_MATCH_TYPE='M'";
            Dataset<Row> dataset =
                    sqlContext.sql(query);
            List<Row> rows = dataset.collectAsList();
            ResultSet rs = new SparkResultSet(rows, dataset.columns());
            assertTrue(rs.next());
            assertEquals(2, rs.getLong(1));
            assertFalse(rs.next());
            query =
                    "select count(*) from " + tableName1 + " where ENTITY_INSTANCE_ID = 40 and CASE_MATCH_TYPE='M' " +
                            " OR CANCELLATION_FLAG <> 'Y'";
            dataset =
                    sqlContext.sql(query);
            rows = dataset.collectAsList();
            rs = new SparkResultSet(rows, dataset.columns());
            assertTrue(rs.next());
            assertEquals(4, rs.getLong(1));
            assertFalse(rs.next());
        }
    }

    @Test
    public void testOrderByWithExpression() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);

        try {
            String tableName = generateUniqueName();
            String ddl = "CREATE TABLE " + tableName +
                    "  (a_string varchar not null, col1 integer, col2 integer, col3 timestamp, col4 varchar" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
            createTestTable(getUrl(), ddl);

            Date date = new Date(System.currentTimeMillis());
            String dml = "UPSERT INTO " + tableName + " VALUES(?, ?, ?, ?, ?)";
            PreparedStatement stmt = conn.prepareStatement(dml);
            stmt.setString(1, "a");
            stmt.setInt(2, 40);
            stmt.setInt(3, 20);
            stmt.setDate(4, new Date(date.getTime()));
            stmt.setString(5, "xxyy");
            stmt.execute();
            stmt.setString(1, "b");
            stmt.setInt(2, 50);
            stmt.setInt(3, 30);
            stmt.setDate(4, new Date(date.getTime()-500));
            stmt.setString(5, "yyzz");
            stmt.execute();
            stmt.setString(1, "c");
            stmt.setInt(2, 60);
            stmt.setInt(3, 20);
            stmt.setDate(4, new Date(date.getTime()-300));
            stmt.setString(5, "ddee");
            stmt.execute();
            conn.commit();

            SQLContext sqlContext = SparkUtil.getSparkSession().sqlContext();
            Dataset phoenixDataSet = SparkUtil.getSparkSession().read().format("phoenix")
                    .option("table", tableName)
                    .option(PhoenixDataSource.ZOOKEEPER_URL, getUrl()).load();
            phoenixDataSet.createOrReplaceTempView(tableName);
            Dataset<Row> dataset =
                    sqlContext.sql("SELECT col1+col2, col4, a_string FROM " + tableName
                            + " ORDER BY col1+col2, col4");
            List<Row> rows = dataset.collectAsList();
            ResultSet rs = new SparkResultSet(rows, dataset.columns());
            assertTrue(rs.next());
            assertEquals("a", rs.getString(3));
            assertTrue(rs.next());
            assertEquals("c", rs.getString(3));
            assertTrue(rs.next());
            assertEquals("b", rs.getString(3));
            assertFalse(rs.next());
        } catch (SQLException e) {
        } finally {
            conn.close();
        }
    }

    @Test
    public void testColumnFamily() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
            conn.setAutoCommit(false);
            String tableName = generateUniqueName();
            String ddl = "CREATE TABLE " + tableName +
                    "  (a_string varchar not null, cf1.a integer, cf1.b varchar, col1 integer, cf2.c varchar, cf2.d integer, col2 integer" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
            createTestTable(getUrl(), ddl);
            String dml = "UPSERT INTO " + tableName + " VALUES(?,?,?,?,?,?,?)";
            PreparedStatement stmt = conn.prepareStatement(dml);
            stmt.setString(1, "a");
            stmt.setInt(2, 40);
            stmt.setString(3, "aa");
            stmt.setInt(4, 10);
            stmt.setString(5, "bb");
            stmt.setInt(6, 20);
            stmt.setInt(7, 1);
            stmt.execute();
            stmt.setString(1, "c");
            stmt.setInt(2, 30);
            stmt.setString(3, "cc");
            stmt.setInt(4, 50);
            stmt.setString(5, "dd");
            stmt.setInt(6, 60);
            stmt.setInt(7, 3);
            stmt.execute();
            stmt.setString(1, "b");
            stmt.setInt(2, 40);
            stmt.setString(3, "bb");
            stmt.setInt(4, 5);
            stmt.setString(5, "aa");
            stmt.setInt(6, 80);
            stmt.setInt(7, 2);
            stmt.execute();
            conn.commit();


            SQLContext sqlContext = SparkUtil.getSparkSession().sqlContext();
            Dataset phoenixDataSet = SparkUtil.getSparkSession().read().format("phoenix")
                    .option("table", tableName)
                    .option(PhoenixDataSource.ZOOKEEPER_URL, getUrl()).load();
            phoenixDataSet.createOrReplaceTempView(tableName);
            Dataset<Row> dataset =
                    sqlContext.sql("SELECT A_STRING, `CF1.A`, `CF1.B`, COL1, `CF2.C`, `CF2.D`, COL2 from "
                            + tableName + " ORDER BY `CF1.A`,`CF2.C`");
            List<Row> rows = dataset.collectAsList();
            ResultSet rs = new SparkResultSet(rows, dataset.columns());

            assertTrue(rs.next());
            assertEquals("c",rs.getString(1));
            assertEquals(30,rs.getInt(2));
            assertEquals("cc",rs.getString(3));
            assertEquals(50,rs.getInt(4));
            assertEquals("dd",rs.getString(5));
            assertEquals(60,rs.getInt(6));
            assertEquals(3,rs.getInt(7));
            assertTrue(rs.next());
            assertEquals("b",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertEquals("bb",rs.getString(3));
            assertEquals(5,rs.getInt(4));
            assertEquals("aa",rs.getString(5));
            assertEquals(80,rs.getInt(6));
            assertEquals(2,rs.getInt(7));
            assertTrue(rs.next());
            assertEquals("a",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertEquals("aa",rs.getString(3));
            assertEquals(10,rs.getInt(4));
            assertEquals("bb",rs.getString(5));
            assertEquals(20,rs.getInt(6));
            assertEquals(1,rs.getInt(7));
            assertFalse(rs.next());

            dataset =
                    sqlContext.sql("SELECT A_STRING, `CF1.A`, `CF1.B`, COL1, `CF2.C`, `CF2.D`, COL2 from "
                            + tableName + " ORDER BY COL2");
            rows = dataset.collectAsList();
            rs = new SparkResultSet(rows, dataset.columns());

            assertTrue(rs.next());
            assertEquals("a",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertEquals("aa",rs.getString(3));
            assertEquals(10,rs.getInt(4));
            assertEquals("bb",rs.getString(5));
            assertEquals(20,rs.getInt(6));
            assertEquals(1,rs.getInt(7));
            assertTrue(rs.next());
            assertEquals("b",rs.getString(1));
            assertEquals(40,rs.getInt(2));
            assertEquals("bb",rs.getString(3));
            assertEquals(5,rs.getInt(4));
            assertEquals("aa",rs.getString(5));
            assertEquals(80,rs.getInt(6));
            assertEquals(2,rs.getInt(7));
            assertTrue(rs.next());
            assertEquals("c",rs.getString(1));
            assertEquals(30,rs.getInt(2));
            assertEquals("cc",rs.getString(3));
            assertEquals(50,rs.getInt(4));
            assertEquals("dd",rs.getString(5));
            assertEquals(60,rs.getInt(6));
            assertEquals(3,rs.getInt(7));
            assertFalse(rs.next());
        }
    }

    @Test
    @Ignore
    public void testOrderByNullable() throws SQLException {

    }
}
