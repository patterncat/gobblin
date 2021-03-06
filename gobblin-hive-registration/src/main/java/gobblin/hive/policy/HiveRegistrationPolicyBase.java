/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.hive.policy;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.ConstructorUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.TableType;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import gobblin.annotation.Alpha;
import gobblin.configuration.ConfigurationKeys;
import gobblin.configuration.State;
import gobblin.hive.HivePartition;
import gobblin.hive.HiveRegProps;
import gobblin.hive.HiveSerDeManager;
import gobblin.hive.HiveTable;
import gobblin.hive.spec.HiveSpec;
import gobblin.hive.spec.SimpleHiveSpec;


/**
 * A base implementation of {@link HiveRegistrationPolicy}. It obtains database name from
 * property {@link #HIVE_DATABASE_NAME} or {@link #HIVE_DATABASE_REGEX} (group 1), obtains
 * table name from property {@link #HIVE_TABLE_NAME} and {@link #HIVE_TABLE_REGEX} (group 1),
 * and builds a {@link SimpleHiveSpec}.
 *
 * @author Ziyang Liu
 */
@Alpha
public class HiveRegistrationPolicyBase implements HiveRegistrationPolicy {

  public static final String HIVE_DATABASE_NAME = "hive.database.name";
  public static final String ADDITIONAL_HIVE_DATABASE_NAMES = "additional.hive.database.names";
  public static final String HIVE_DATABASE_REGEX = "hive.database.regex";
  public static final String HIVE_DATABASE_NAME_PREFIX = "hive.database.name.prefix";
  public static final String HIVE_DATABASE_NAME_SUFFIX = "hive.database.name.suffix";
  public static final String HIVE_TABLE_NAME = "hive.table.name";
  public static final String ADDITIONAL_HIVE_TABLE_NAMES = "additional.hive.table.names";
  public static final String HIVE_TABLE_REGEX = "hive.table.regex";
  public static final String HIVE_TABLE_NAME_PREFIX = "hive.table.name.prefix";
  public static final String HIVE_TABLE_NAME_SUFFIX = "hive.table.name.suffix";
  public static final String HIVE_SANITIZE_INVALID_NAMES = "hive.sanitize.invalid.names";
  public static final String HIVE_FS_URI = "hive.registration.fs.uri";

  /**
   * A valid db or table name should start with an alphanumeric character, and contains only
   * alphanumeric characters and '_'.
   */
  private static final Pattern VALID_DB_TABLE_NAME_PATTERN_1 = Pattern.compile("[a-z0-9][a-z0-9_]*");

  /**
   * A valid db or table name should contain at least one letter or '_' (i.e., should not be numbers only).
   */
  private static final Pattern VALID_DB_TABLE_NAME_PATTERN_2 = Pattern.compile(".*[a-z_].*");

  protected final HiveRegProps props;
  protected final FileSystem fs;
  protected final boolean sanitizeNameAllowed;
  protected final Optional<Pattern> dbNamePattern;
  protected final Optional<Pattern> tableNamePattern;
  protected final String dbNamePrefix;
  protected final String dbNameSuffix;
  protected final String tableNamePrefix;
  protected final String tableNameSuffix;

  public HiveRegistrationPolicyBase(State props) throws IOException {
    Preconditions.checkNotNull(props);
    this.props = new HiveRegProps(props);
    if (props.contains(HiveRegistrationPolicyBase.HIVE_FS_URI)) {
      this.fs = FileSystem.get(URI.create(props.getProp(HiveRegistrationPolicyBase.HIVE_FS_URI)), new Configuration());
    } else {
      this.fs = FileSystem.get(new Configuration());
    }
    this.sanitizeNameAllowed = props.getPropAsBoolean(HIVE_SANITIZE_INVALID_NAMES, true);
    this.dbNamePattern = props.contains(HIVE_DATABASE_REGEX)
        ? Optional.of(Pattern.compile(props.getProp(HIVE_DATABASE_REGEX))) : Optional.<Pattern> absent();
    this.tableNamePattern = props.contains(HIVE_TABLE_REGEX)
        ? Optional.of(Pattern.compile(props.getProp(HIVE_TABLE_REGEX))) : Optional.<Pattern> absent();
    this.dbNamePrefix = props.getProp(HIVE_DATABASE_NAME_PREFIX, StringUtils.EMPTY);
    this.dbNameSuffix = props.getProp(HIVE_DATABASE_NAME_SUFFIX, StringUtils.EMPTY);
    this.tableNamePrefix = props.getProp(HIVE_TABLE_NAME_PREFIX, StringUtils.EMPTY);
    this.tableNameSuffix = props.getProp(HIVE_TABLE_NAME_SUFFIX, StringUtils.EMPTY);
  }

  /**
   * This method first tries to obtain the database name from {@link #HIVE_DATABASE_NAME}.
   * If this property is not specified, it then tries to obtain the database name using
   * the first group of {@link #HIVE_DATABASE_REGEX}.
   *
   */
  protected Optional<String> getDatabaseName(Path path) {
    if (!this.props.contains(HIVE_DATABASE_NAME) && !this.props.contains(HIVE_DATABASE_REGEX)) {
      return Optional.<String> absent();
    }

    return Optional.<String> of(
        this.dbNamePrefix + getDatabaseOrTableName(path, HIVE_DATABASE_NAME, HIVE_DATABASE_REGEX, this.dbNamePattern)
            + this.dbNameSuffix);
  }

  /**
   * Obtain Hive database names. The returned {@link Iterable} contains the database name returned by
   * {@link #getDatabaseName(Path)} (if present) plus additional database names specified in
   * {@link #ADDITIONAL_HIVE_DATABASE_NAMES}.
   *
   */
  protected Iterable<String> getDatabaseNames(Path path) {
    List<String> databaseNames = Lists.newArrayList();

    Optional<String> databaseName;
    if ((databaseName = getDatabaseName(path)).isPresent()) {
      databaseNames.add(databaseName.get());
    }

    if (!Strings.isNullOrEmpty(this.props.getProp(ADDITIONAL_HIVE_DATABASE_NAMES))) {
      for (String additionalDbName : this.props.getPropAsList(ADDITIONAL_HIVE_DATABASE_NAMES)) {
        databaseNames.add(this.dbNamePrefix + additionalDbName + this.dbNameSuffix);
      }
    }

    Preconditions.checkState(!databaseNames.isEmpty(), "Hive database name not specified");
    return databaseNames;
  }

  /**
   * This method first tries to obtain the database name from {@link #HIVE_TABLE_NAME}.
   * If this property is not specified, it then tries to obtain the database name using
   * the first group of {@link #HIVE_TABLE_REGEX}.
   */
  protected Optional<String> getTableName(Path path) {
    if (!this.props.contains(HIVE_TABLE_NAME) && !this.props.contains(HIVE_TABLE_REGEX)) {
      return Optional.<String> absent();
    }

    return Optional.<String> of(
        this.tableNamePrefix + getDatabaseOrTableName(path, HIVE_TABLE_NAME, HIVE_TABLE_REGEX, this.tableNamePattern)
            + this.tableNameSuffix);
  }

  /**
   * Obtain Hive table names. The returned {@link Iterable} contains the table name returned by
   * {@link #getTableName(Path)} (if present) plus additional table names specified in
   * {@link #ADDITIONAL_HIVE_TABLE_NAMES}.
   */
  protected Iterable<String> getTableNames(Path path) {
    List<String> tableNames = Lists.newArrayList();

    Optional<String> tableName;
    if ((tableName = getTableName(path)).isPresent()) {
      tableNames.add(tableName.get());
    }

    if (!Strings.isNullOrEmpty(this.props.getProp(ADDITIONAL_HIVE_TABLE_NAMES))) {
      for (String additionalTableName : this.props.getPropAsList(ADDITIONAL_HIVE_TABLE_NAMES)) {
        tableNames.add(this.tableNamePrefix + additionalTableName + this.tableNameSuffix);
      }
    }

    Preconditions.checkState(!tableNames.isEmpty(), "Hive table name not specified");
    return tableNames;
  }

  protected String getDatabaseOrTableName(Path path, String nameKey, String regexKey, Optional<Pattern> pattern) {
    String name;
    if (this.props.contains(nameKey)) {
      name = this.props.getProp(nameKey);
    } else if (pattern.isPresent()) {
      name = pattern.get().matcher(path.toString()).group();
    } else {
      throw new IllegalStateException("Missing required property " + nameKey + " or " + regexKey);
    }

    return sanitizeAndValidateName(name);
  }

  protected String sanitizeAndValidateName(String name) {
    name = name.toLowerCase();

    if (this.sanitizeNameAllowed && !isNameValid(name)) {
      name = sanitizeName(name);
    }

    if (isNameValid(name)) {
      return name;
    }
    throw new IllegalStateException(name + " is not a valid Hive database or table name");
  }

  /**
   * A base implementation for creating {@link HiveTable}s given a {@link Path}.
   *
   * <p>
   *   This method returns a list of {@link Hivetable}s that contains one table per db name
   *   (returned by {@link #getDatabaseNames(Path)}) and table name (returned by {@link #getTableNames(Path)}.
   * </p>
   *
   * @param path a {@link Path} used to create the {@link HiveTable}.
   * @return a list of {@link HiveTable}s for the given {@link Path}.
   * @throws IOException
   */
  protected List<HiveTable> getTables(Path path) throws IOException {
    List<HiveTable> tables = Lists.newArrayList();

    for (String databaseName : getDatabaseNames(path)) {
      for (String tableName : getTableNames(path)) {
        tables.add(getTable(path, databaseName, tableName));
      }
    }
    return tables;
  }

  /**
   * A base implementation for creating a non bucketed, external {@link HiveTable} for a {@link Path}.
   *
   * @param path a {@link Path} used to create the {@link HiveTable}.
   * @param dbName the database name for the created {@link HiveTable}.
   * @param tableName the table name for the created {@link HiveTable}.
   * @return a {@link HiveTable}s for the given {@link Path}.
   * @throws IOException
   */
  protected HiveTable getTable(Path path, String dbName, String tableName) throws IOException {
    HiveTable table = new HiveTable.Builder().withDbName(dbName).withTableName(tableName)
        .withSerdeManaager(HiveSerDeManager.get(this.props)).build();

    table.setLocation(this.fs.makeQualified(getTableLocation(path)).toString());
    table.setSerDeProps(path);
    table.setProps(this.props.getTablePartitionProps());
    table.setStorageProps(this.props.getStorageProps());
    table.setSerDeProps(this.props.getSerdeProps());
    table.setNumBuckets(-1);
    table.setBucketColumns(Lists.<String> newArrayList());
    table.setTableType(TableType.EXTERNAL_TABLE.toString());
    return table;
  }

  protected Optional<HivePartition> getPartition(Path path, HiveTable table) throws IOException {
    return Optional.<HivePartition> absent();
  }

  protected Path getTableLocation(Path path) {
    return path;
  }

  /**
   * Determine whether a database or table name is valid.
   *
   * A name is valid if and only if: it starts with an alphanumeric character, contains only alphanumeric characters
   * and '_', and is NOT composed of numbers only.
   */
  protected static boolean isNameValid(String name) {
    Preconditions.checkNotNull(name);
    name = name.toLowerCase();
    return VALID_DB_TABLE_NAME_PATTERN_1.matcher(name).matches()
        && VALID_DB_TABLE_NAME_PATTERN_2.matcher(name).matches();
  }

  /**
   * Attempt to sanitize an invalid database or table name by replacing characters that are not alphanumeric
   * or '_' with '_'.
   */
  protected static String sanitizeName(String name) {
    return name.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  @Override
  public Collection<HiveSpec> getHiveSpecs(Path path) throws IOException {
    List<HiveSpec> specs = Lists.newArrayList();
    for (HiveTable table : getTables(path)) {
      specs.add(new SimpleHiveSpec.Builder<>(path).withTable(table).withPartition(getPartition(path, table)).build());
    }
    return specs;
  }

  /**
   * Get a {@link HiveRegistrationPolicy} from a {@link State} object.
   *
   * @param props A {@link State} object that contains property, {@link #HIVE_REGISTRATION_POLICY},
   * which is the class name of the desired policy. This policy class must have a constructor that
   * takes a {@link State} object.
   */
  public static HiveRegistrationPolicy getPolicy(State props) {
    Preconditions.checkArgument(props.contains(ConfigurationKeys.HIVE_REGISTRATION_POLICY));

    String policyType = props.getProp(ConfigurationKeys.HIVE_REGISTRATION_POLICY);
    try {
      return (HiveRegistrationPolicy) ConstructorUtils.invokeConstructor(Class.forName(policyType), props);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(
          "Unable to instantiate " + HiveRegistrationPolicy.class.getSimpleName() + " with type " + policyType, e);
    }
  }
}
