/**
 * Copyright 2010-2015 Axel Fontaine
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core;


import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.FlywayCallback;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.internal.callback.SqlScriptFlywayCallback;
import org.flywaydb.core.internal.command.DbBaseline;
import org.flywaydb.core.internal.command.DbClean;
import org.flywaydb.core.internal.command.DbMigrate;
import org.flywaydb.core.internal.command.DbRepair;
import org.flywaydb.core.internal.command.DbSchemas;
import org.flywaydb.core.internal.command.DbValidate;
import org.flywaydb.core.internal.dbsupport.DbSupport;
import org.flywaydb.core.internal.dbsupport.DbSupportFactory;
import org.flywaydb.core.internal.dbsupport.Schema;
import org.flywaydb.core.internal.info.MigrationInfoServiceImpl;
import org.flywaydb.core.internal.metadatatable.MetaDataTable;
import org.flywaydb.core.internal.metadatatable.MetaDataTableImpl;
import org.flywaydb.core.internal.resolver.CompositeMigrationResolver;
import org.flywaydb.core.internal.util.ClassUtils;
import org.flywaydb.core.internal.util.Locations;
import org.flywaydb.core.internal.util.PlaceholderReplacer;
import org.flywaydb.core.internal.util.StringUtils;
import org.flywaydb.core.internal.util.VersionPrinter;
import org.flywaydb.core.internal.util.jdbc.DriverDataSource;
import org.flywaydb.core.internal.util.jdbc.JdbcUtils;
import org.flywaydb.core.internal.util.logging.Log;
import org.flywaydb.core.internal.util.logging.LogFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * This is the centre point of Flyway, and for most users, the only class they will ever have to deal with.
 * <p>
 * It is THE public API from which all important Flyway functions such as clean, validate and migrate can be called.
 * </p>
 */
public class Flyway {
    private static final Log LOG = LogFactory.getLog(Flyway.class);

    /**
     * Property name prefix for placeholders that are configured through properties.
     */
    private static final String PLACEHOLDERS_PROPERTY_PREFIX = "flyway.placeholders.";

    /**
     * The locations to scan recursively for migrations.
     * <p/>
     * <p>The location type is determined by its prefix.
     * Unprefixed locations or locations starting with {@code classpath:} point to a package on the classpath and may
     * contain both sql and java-based migrations.
     * Locations starting with {@code filesystem:} point to a directory on the filesystem and may only contain sql
     * migrations.</p>
     * <p/>
     * (default: db/migration)
     */
    private Locations locations = new Locations("db/migration");

    /**
     * The encoding of Sql migrations. (default: UTF-8)
     */
    private String encoding = "UTF-8";

    /**
     * The schemas managed by Flyway.  These schema names are case-sensitive. (default: The default schema for the datasource connection)
     * <p>Consequences:</p>
     * <ul>
     * <li>The first schema in the list will be automatically set as the default one during the migration.</li>
     * <li>The first schema in the list will also be the one containing the metadata table.</li>
     * <li>The schemas will be cleaned in the order of this list.</li>
     * </ul>
     */
    private String[] schemaNames = new String[0];

    /**
     * <p>The name of the schema metadata table that will be used by Flyway. (default: schema_version)</p><p> By default
     * (single-schema mode) the metadata table is placed in the default schema for the connection provided by the
     * datasource. </p> <p> When the <i>flyway.schemas</i> property is set (multi-schema mode), the metadata table is
     * placed in the first schema of the list. </p>
     */
    private String table = "schema_version";

    /**
     * The target version up to which Flyway should consider migrations. Migrations with a higher version number will
     * be ignored. The special value {@code current} designates the current version of the schema (default: the latest version)
     */
    private MigrationVersion target = MigrationVersion.LATEST;

    /**
     * Whether placeholders should be replaced. (default: true)
     */
    private boolean placeholderReplacement = true;

    /**
     * The map of &lt;placeholder, replacementValue&gt; to apply to sql migration scripts.
     */
    private Map<String, String> placeholders = new HashMap<String, String>();

    /**
     * The prefix of every placeholder. (default: ${ )
     */
    private String placeholderPrefix = "${";

    /**
     * The suffix of every placeholder. (default: } )
     */
    private String placeholderSuffix = "}";

    /**
     * The file name prefix for sql migrations. (default: V)
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     */
    private String sqlMigrationPrefix = "V";

    /**
     * The file name separator for sql migrations. (default: __)
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     */
    private String sqlMigrationSeparator = "__";

    /**
     * The file name suffix for sql migrations. (default: .sql)
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     */
    private String sqlMigrationSuffix = ".sql";

    /**
     * Ignores failed future migrations when reading the metadata table. These are migrations that were performed by a
     * newer deployment of the application that are not yet available in this version. For example: we have migrations
     * available on the classpath up to version 3.0. The metadata table indicates that a migration to version 4.0
     * (unknown to us) has already been attempted and failed. Instead of bombing out (fail fast) with an exception, a
     * warning is logged and Flyway terminates normally. This is useful for situations where a database rollback is not
     * an option. An older version of the application can then be redeployed, even though a newer one failed due to a
     * bad migration. (default: {@code false})
     */
    private boolean ignoreFailedFutureMigration;

    /**
     * Whether to automatically call validate or not when running migrate. (default: {@code true})
     */
    private boolean validateOnMigrate = true;

    /**
     * Whether to automatically call clean or not when a validation error occurs. (default: {@code false})
     * <p> This is exclusively intended as a convenience for development. Even tough we
     * strongly recommend not to change migration scripts once they have been checked into SCM and run, this provides a
     * way of dealing with this case in a smooth manner. The database will be wiped clean automatically, ensuring that
     * the next migration will bring you back to the state checked into SCM.</p>
     * <p><b>Warning ! Do not enable in production !</b></p>
     */
    private boolean cleanOnValidationError;

    /**
     * The version to tag an existing schema with when executing baseline. (default: 1)
     */
    private MigrationVersion baselineVersion = MigrationVersion.fromVersion("1");

    /**
     * The description to tag an existing schema with when executing baseline. (default: &lt;&lt; Flyway Baseline &gt;&gt;)
     */
    private String baselineDescription = "<< Flyway Baseline >>";

    /**
     * <p>
     * Whether to automatically call baseline when migrate is executed against a non-empty schema with no metadata table.
     * This schema will then be initialized with the {@code baselineVersion} before executing the migrations.
     * Only migrations above {@code baselineVersion} will then be applied.
     * </p>
     * <p>
     * This is useful for initial Flyway production deployments on projects with an existing DB.
     * </p>
     * <p>
     * Be careful when enabling this as it removes the safety net that ensures
     * Flyway does not migrate the wrong database in case of a configuration mistake! (default: {@code false})
     * </p>
     */
    private boolean baselineOnMigrate;

    /**
     * Allows migrations to be run "out of order".
     * <p>If you already have versions 1 and 3 applied, and now a version 2 is found,
     * it will be applied too instead of being ignored.</p>
     * <p>(default: {@code false})</p>
     */
    private boolean outOfOrder;

    /**
     * This is a list of callbacks that fire before and after tasks are executed.  You can
     * add as many custom callbacks as you want.
     */
    private FlywayCallback[] callbacks = new FlywayCallback[0];

    /**
     * The custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply.
     * <p>(default: none)</p>
     */
    private MigrationResolver[] resolvers = new MigrationResolver[0];

    /**
     * Whether Flyway created the DataSource.
     */
    private boolean createdDataSource;

    /**
     * The dataSource to use to access the database. Must have the necessary privileges to execute ddl.
     */
    private DataSource dataSource;

    /**
     * The ClassLoader to use for resolving migrations on the classpath. (default: Thread.currentThread().getContextClassLoader() )
     */
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    /**
     * Whether the database connection info has already been printed in the logs.
     */
    private boolean dbConnectionInfoPrinted;

    /**
     * Creates a new instance of Flyway. This is your starting point.
     */
    public Flyway() {
        // Do nothing
    }

    /**
     * Retrieves the locations to scan recursively for migrations.
     * <p/>
     * <p>The location type is determined by its prefix.
     * Unprefixed locations or locations starting with {@code classpath:} point to a package on the classpath and may
     * contain both sql and java-based migrations.
     * Locations starting with {@code filesystem:} point to a directory on the filesystem and may only contain sql
     * migrations.</p>
     *
     * @return Locations to scan recursively for migrations. (default: db/migration)
     */
    public String[] getLocations() {
        String[] result = new String[locations.getLocations().size()];
        for (int i = 0; i < locations.getLocations().size(); i++) {
            result[i] = locations.getLocations().get(i).toString();
        }
        return result;
    }

    /**
     * Retrieves the encoding of Sql migrations.
     *
     * @return The encoding of Sql migrations. (default: UTF-8)
     */
    public String getEncoding() {
        return encoding;
    }

    /**
     * Retrieves the schemas managed by Flyway.  These schema names are case-sensitive.
     * <p>Consequences:</p>
     * <ul>
     * <li>The first schema in the list will be automatically set as the default one during the migration.</li>
     * <li>The first schema in the list will also be the one containing the metadata table.</li>
     * <li>The schemas will be cleaned in the order of this list.</li>
     * </ul>
     *
     * @return The schemas managed by Flyway. (default: The default schema for the datasource connection)
     */
    public String[] getSchemas() {
        return schemaNames;
    }

    /**
     * <p>Retrieves the name of the schema metadata table that will be used by Flyway.</p><p> By default (single-schema
     * mode) the metadata table is placed in the default schema for the connection provided by the datasource. </p> <p>
     * When the <i>flyway.schemas</i> property is set (multi-schema mode), the metadata table is placed in the first
     * schema of the list. </p>
     *
     * @return The name of the schema metadata table that will be used by flyway. (default: schema_version)
     */
    public String getTable() {
        return table;
    }

    /**
     * Retrieves the target version up to which Flyway should consider migrations.
     * Migrations with a higher version number will be ignored.
     * The special value {@code current} designates the current version of the schema.
     *
     * @return The target version up to which Flyway should consider migrations. (default: the latest version)
     */
    public MigrationVersion getTarget() {
        return target;
    }

    /**
     * Checks whether placeholders should be replaced.
     *
     * @return Whether placeholders should be replaced. (default: true)
     */
    public boolean isPlaceholderReplacement() {
        return placeholderReplacement;
    }

    /**
     * Retrieves the map of &lt;placeholder, replacementValue&gt; to apply to sql migration scripts.
     *
     * @return The map of &lt;placeholder, replacementValue&gt; to apply to sql migration scripts.
     */
    public Map<String, String> getPlaceholders() {
        return placeholders;
    }

    /**
     * Retrieves the prefix of every placeholder.
     *
     * @return The prefix of every placeholder. (default: ${ )
     */
    public String getPlaceholderPrefix() {
        return placeholderPrefix;
    }

    /**
     * Retrieves the suffix of every placeholder.
     *
     * @return The suffix of every placeholder. (default: } )
     */
    public String getPlaceholderSuffix() {
        return placeholderSuffix;
    }

    /**
     * Retrieves the file name prefix for sql migrations.
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @return The file name prefix for sql migrations. (default: V)
     */
    public String getSqlMigrationPrefix() {
        return sqlMigrationPrefix;
    }

    /**
     * Retrieves the file name separator for sql migrations.
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @return The file name separator for sql migrations. (default: __)
     */
    public String getSqlMigrationSeparator() {
        return sqlMigrationSeparator;
    }

    /**
     * Retrieves the file name suffix for sql migrations.
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @return The file name suffix for sql migrations. (default: .sql)
     */
    public String getSqlMigrationSuffix() {
        return sqlMigrationSuffix;
    }

    /**
     * Whether to ignore failed future migrations when reading the metadata table. These are migrations that
     * were performed by a newer deployment of the application that are not yet available in this version. For example:
     * we have migrations available on the classpath up to version 3.0. The metadata table indicates that a migration to
     * version 4.0 (unknown to us) has already been attempted and failed. Instead of bombing out (fail fast) with an
     * exception, a warning is logged and Flyway terminates normally. This is useful for situations where a database
     * rollback is not an option. An older version of the application can then be redeployed, even though a newer one
     * failed due to a bad migration.
     *
     * @return {@code true} to terminate normally and log a warning, {@code false} to fail fast with an exception.
     * (default: {@code false})
     */
    public boolean isIgnoreFailedFutureMigration() {
        return ignoreFailedFutureMigration;
    }

    /**
     * Whether to automatically call validate or not when running migrate.
     *
     * @return {@code true} if validate should be called. {@code false} if not. (default: {@code true})
     */
    public boolean isValidateOnMigrate() {
        return validateOnMigrate;
    }

    /**
     * Whether to automatically call clean or not when a validation error occurs.
     * <p> This is exclusively intended as a convenience for development. Even tough we
     * strongly recommend not to change migration scripts once they have been checked into SCM and run, this provides a
     * way of dealing with this case in a smooth manner. The database will be wiped clean automatically, ensuring that
     * the next migration will bring you back to the state checked into SCM.</p>
     * <p><b>Warning ! Do not enable in production !</b></p>
     *
     * @return {@code true} if clean should be called. {@code false} if not. (default: {@code false})
     */
    public boolean isCleanOnValidationError() {
        return cleanOnValidationError;
    }

    /**
     * Retrieves the version to tag an existing schema with when executing baseline.
     *
     * @return The version to tag an existing schema with when executing baseline. (default: 1)
     */
    public MigrationVersion getBaselineVersion() {
        return baselineVersion;
    }

    /**
     * Retrieves the description to tag an existing schema with when executing baseline.
     *
     * @return The description to tag an existing schema with when executing baseline. (default: &lt;&lt; Flyway Baseline &gt;&gt;)
     */
    public String getBaselineDescription() {
        return baselineDescription;
    }

    /**
     * <p>
     * Whether to automatically call baseline when migrate is executed against a non-empty schema with no metadata table.
     * This schema will then be initialized with the {@code baselineVersion} before executing the migrations.
     * Only migrations above {@code baselineVersion} will then be applied.
     * </p>
     * <p>
     * This is useful for initial Flyway production deployments on projects with an existing DB.
     * </p>
     * <p>
     * Be careful when enabling this as it removes the safety net that ensures
     * Flyway does not migrate the wrong database in case of a configuration mistake!
     * </p>
     *
     * @return {@code true} if baseline should be called on migrate for non-empty schemas, {@code false} if not. (default: {@code false})
     */
    public boolean isBaselineOnMigrate() {
        return baselineOnMigrate;
    }

    /**
     * Retrieves the version to tag an existing schema with when executing baseline.
     *
     * @return The version to tag an existing schema with when executing baseline. (default: 1)
     * @deprecated Use getBaselineVersion() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public MigrationVersion getInitVersion() {
        LOG.warn("Flyway.getInitVersion() is deprecated. Use getBaselineVersion() instead. Will be removed in Flyway 4.0.");
        return baselineVersion;
    }

    /**
     * Retrieves the description to tag an existing schema with when executing baseline.
     *
     * @return The description to tag an existing schema with when executing baseline. (default: &lt;&lt; Flyway Baseline &gt;&gt;)
     * @deprecated Use getBaselineDescription() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public String getInitDescription() {
        LOG.warn("Flyway.getBaselineDescription() is deprecated. Use getBaselineDescription() instead. Will be removed in Flyway 4.0.");
        return baselineDescription;
    }

    /**
     * <p>
     * Whether to automatically call baseline when migrate is executed against a non-empty schema with no metadata table.
     * This schema will then be initialized with the {@code baselineVersion} before executing the migrations.
     * Only migrations above {@code baselineVersion} will then be applied.
     * </p>
     * <p>
     * This is useful for initial Flyway production deployments on projects with an existing DB.
     * </p>
     * <p>
     * Be careful when enabling this as it removes the safety net that ensures
     * Flyway does not migrate the wrong database in case of a configuration mistake!
     * </p>
     *
     * @return {@code true} if baseline should be called on migrate for non-empty schemas, {@code false} if not. (default: {@code false})
     * @deprecated Use getBaselineDescription() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public boolean isInitOnMigrate() {
        LOG.warn("Flyway.isInitOnMigrate() is deprecated. Use isBaselineOnMigrate() instead. Will be removed in Flyway 4.0.");
        return baselineOnMigrate;
    }

    /**
     * Allows migrations to be run "out of order".
     * <p>If you already have versions 1 and 3 applied, and now a version 2 is found,
     * it will be applied too instead of being ignored.</p>
     *
     * @return {@code true} if outOfOrder migrations should be applied, {@code false} if not. (default: {@code false})
     */
    public boolean isOutOfOrder() {
        return outOfOrder;
    }

    /**
     * Retrieves the The custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply.
     *
     * @return The custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply. An empty array if none.
     * (default: none)
     */
    public MigrationResolver[] getResolvers() {
        return resolvers;
    }

    /**
     * Retrieves the dataSource to use to access the database. Must have the necessary privileges to execute ddl.
     *
     * @return The dataSource to use to access the database. Must have the necessary privileges to execute ddl.
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Retrieves the ClassLoader to use for resolving migrations on the classpath.
     *
     * @return The ClassLoader to use for resolving migrations on the classpath.
     * (default: Thread.currentThread().getContextClassLoader() )
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Ignores failed future migrations when reading the metadata table. These are migrations that were performed by a
     * newer deployment of the application that are not yet available in this version. For example: we have migrations
     * available on the classpath up to version 3.0. The metadata table indicates that a migration to version 4.0
     * (unknown to us) has already been attempted and failed. Instead of bombing out (fail fast) with an exception, a
     * warning is logged and Flyway terminates normally. This is useful for situations where a database rollback is not
     * an option. An older version of the application can then be redeployed, even though a newer one failed due to a
     * bad migration.
     *
     * @param ignoreFailedFutureMigration {@code true} to terminate normally and log a warning, {@code false} to fail
     *                                    fast with an exception. (default: {@code false})
     */
    public void setIgnoreFailedFutureMigration(boolean ignoreFailedFutureMigration) {
        this.ignoreFailedFutureMigration = ignoreFailedFutureMigration;
    }

    /**
     * Whether to automatically call validate or not when running migrate.
     *
     * @param validateOnMigrate {@code true} if validate should be called. {@code false} if not. (default: {@code true})
     */
    public void setValidateOnMigrate(boolean validateOnMigrate) {
        this.validateOnMigrate = validateOnMigrate;
    }

    /**
     * Whether to automatically call clean or not when a validation error occurs.
     * <p> This is exclusively intended as a convenience for development. Even tough we
     * strongly recommend not to change migration scripts once they have been checked into SCM and run, this provides a
     * way of dealing with this case in a smooth manner. The database will be wiped clean automatically, ensuring that
     * the next migration will bring you back to the state checked into SCM.</p>
     * <p><b>Warning ! Do not enable in production !</b></p>
     *
     * @param cleanOnValidationError {@code true} if clean should be called. {@code false} if not. (default: {@code false})
     */
    public void setCleanOnValidationError(boolean cleanOnValidationError) {
        this.cleanOnValidationError = cleanOnValidationError;
    }

    /**
     * Sets the locations to scan recursively for migrations.
     * <p/>
     * <p>The location type is determined by its prefix.
     * Unprefixed locations or locations starting with {@code classpath:} point to a package on the classpath and may
     * contain both sql and java-based migrations.
     * Locations starting with {@code filesystem:} point to a directory on the filesystem and may only contain sql
     * migrations.</p>
     *
     * @param locations Locations to scan recursively for migrations. (default: db/migration)
     */
    public void setLocations(String... locations) {
        this.locations = new Locations(locations);
    }

    /**
     * Sets the encoding of Sql migrations.
     *
     * @param encoding The encoding of Sql migrations. (default: UTF-8)
     */
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    /**
     * Sets the schemas managed by Flyway. These schema names are case-sensitive. (default: The default schema for the datasource connection)
     * <p>Consequences:</p>
     * <ul>
     * <li>The first schema in the list will be automatically set as the default one during the migration.</li>
     * <li>The first schema in the list will also be the one containing the metadata table.</li>
     * <li>The schemas will be cleaned in the order of this list.</li>
     * </ul>
     *
     * @param schemas The schemas managed by Flyway. May not be {@code null}. Must contain at least one element.
     */
    public void setSchemas(String... schemas) {
        this.schemaNames = schemas;
    }

    /**
     * <p>Sets the name of the schema metadata table that will be used by Flyway.</p><p> By default (single-schema mode)
     * the metadata table is placed in the default schema for the connection provided by the datasource. </p> <p> When
     * the <i>flyway.schemas</i> property is set (multi-schema mode), the metadata table is placed in the first schema
     * of the list. </p>
     *
     * @param table The name of the schema metadata table that will be used by flyway. (default: schema_version)
     */
    public void setTable(String table) {
        this.table = table;
    }

    /**
     * Sets the target version up to which Flyway should consider migrations. Migrations with a higher version number will
     * be ignored.
     *
     * @param target The target version up to which Flyway should consider migrations. (default: the latest version)
     */
    public void setTarget(MigrationVersion target) {
        this.target = target;
    }

    /**
     * Sets the target version up to which Flyway should consider migrations.
     * Migrations with a higher version number will be ignored.
     *
     * @param target The target version up to which Flyway should consider migrations.
     *               The special value {@code current} designates the current version of the schema. (default: the latest
     *               version)
     * @deprecated Will be removed in Flyway 4.0. Use setTargetAsString(String) instead.
     */
    @Deprecated
    public void setTarget(String target) {
        LOG.warn("Flyway.setTarget(String) is deprecated and will be removed in Flyway 4.0. Use setTargetAsString(String) instead.");
        this.target = MigrationVersion.fromVersion(target);
    }

    /**
     * Sets the target version up to which Flyway should consider migrations.
     * Migrations with a higher version number will be ignored.
     *
     * @param target The target version up to which Flyway should consider migrations.
     *               The special value {@code current} designates the current version of the schema. (default: the latest
     *               version)
     */
    public void setTargetAsString(String target) {
        this.target = MigrationVersion.fromVersion(target);
    }

    /**
     * Sets whether placeholders should be replaced.
     *
     * @param placeholderReplacement Whether placeholders should be replaced. (default: true)
     */
    public void setPlaceholderReplacement(boolean placeholderReplacement) {
        this.placeholderReplacement = placeholderReplacement;
    }

    /**
     * Sets the placeholders to replace in sql migration scripts.
     *
     * @param placeholders The map of &lt;placeholder, replacementValue&gt; to apply to sql migration scripts.
     */
    public void setPlaceholders(Map<String, String> placeholders) {
        this.placeholders = placeholders;
    }

    /**
     * Sets the prefix of every placeholder.
     *
     * @param placeholderPrefix The prefix of every placeholder. (default: ${ )
     */
    public void setPlaceholderPrefix(String placeholderPrefix) {
        if (!StringUtils.hasLength(placeholderPrefix)) {
            throw new FlywayException("placeholderPrefix cannot be empty!");
        }
        this.placeholderPrefix = placeholderPrefix;
    }

    /**
     * Sets the suffix of every placeholder.
     *
     * @param placeholderSuffix The suffix of every placeholder. (default: } )
     */
    public void setPlaceholderSuffix(String placeholderSuffix) {
        if (!StringUtils.hasLength(placeholderSuffix)) {
            throw new FlywayException("placeholderSuffix cannot be empty!");
        }
        this.placeholderSuffix = placeholderSuffix;
    }

    /**
     * Sets the file name prefix for sql migrations.
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @param sqlMigrationPrefix The file name prefix for sql migrations (default: V)
     */
    public void setSqlMigrationPrefix(String sqlMigrationPrefix) {
        this.sqlMigrationPrefix = sqlMigrationPrefix;
    }

    /**
     * Sets the file name separator for sql migrations.
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @param sqlMigrationSeparator The file name separator for sql migrations (default: __)
     */
    public void setSqlMigrationSeparator(String sqlMigrationSeparator) {
        if (!StringUtils.hasLength(sqlMigrationSeparator)) {
            throw new FlywayException("sqlMigrationSeparator cannot be empty!");
        }

        this.sqlMigrationSeparator = sqlMigrationSeparator;
    }

    /**
     * Sets the file name suffix for sql migrations.
     * <p/>
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @param sqlMigrationSuffix The file name suffix for sql migrations (default: .sql)
     */
    public void setSqlMigrationSuffix(String sqlMigrationSuffix) {
        this.sqlMigrationSuffix = sqlMigrationSuffix;
    }

    /**
     * Sets the datasource to use. Must have the necessary privileges to execute ddl.
     *
     * @param dataSource The datasource to use. Must have the necessary privileges to execute ddl.
     */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        createdDataSource = false;
    }

    /**
     * Sets the datasource to use. Must have the necessary privileges to execute ddl.
     * <p/>
     * <p>To use a custom ClassLoader, setClassLoader() must be called prior to calling this method.</p>
     *
     * @param url      The JDBC URL of the database.
     * @param user     The user of the database.
     * @param password The password of the database.
     * @param initSqls The (optional) sql statements to execute to initialize a connection immediately after obtaining it.
     */
    public void setDataSource(String url, String user, String password, String... initSqls) {
        this.dataSource = new DriverDataSource(classLoader, null, url, user, password, initSqls);
        createdDataSource = true;
    }

    /**
     * Sets the ClassLoader to use for resolving migrations on the classpath.
     *
     * @param classLoader The ClassLoader to use for resolving migrations on the classpath. (default: Thread.currentThread().getContextClassLoader() )
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Sets the version to tag an existing schema with when executing baseline.
     *
     * @param baselineVersion The version to tag an existing schema with when executing baseline. (default: 1)
     */
    public void setBaselineVersion(MigrationVersion baselineVersion) {
        this.baselineVersion = baselineVersion;
    }

    /**
     * Sets the version to tag an existing schema with when executing baseline.
     *
     * @param baselineVersion The version to tag an existing schema with when executing baseline. (default: 1)
     * @deprecated Will be removed in Flyway 4.0. Use setBaselineVersionAsString(String) instead.
     */
    @Deprecated
    public void setBaselineVersion(String baselineVersion) {
        LOG.warn("Flyway.setBaselineVersion(String) is deprecated and will be removed in Flyway 4.0. Use setBaselineVersionAsString(String) instead.");
        this.baselineVersion = MigrationVersion.fromVersion(baselineVersion);
    }

    /**
     * Sets the version to tag an existing schema with when executing baseline.
     *
     * @param baselineVersion The version to tag an existing schema with when executing baseline. (default: 1)
     */
    public void setBaselineVersionAsString(String baselineVersion) {
        this.baselineVersion = MigrationVersion.fromVersion(baselineVersion);
    }

    /**
     * Sets the description to tag an existing schema with when executing baseline.
     *
     * @param baselineDescription The description to tag an existing schema with when executing baseline. (default: &lt;&lt; Flyway Baseline &gt;&gt;)
     */
    public void setBaselineDescription(String baselineDescription) {
        this.baselineDescription = baselineDescription;
    }

    /**
     * <p>
     * Whether to automatically call baseline when migrate is executed against a non-empty schema with no metadata table.
     * This schema will then be baselined with the {@code baselineVersion} before executing the migrations.
     * Only migrations above {@code baselineVersion} will then be applied.
     * </p>
     * <p>
     * This is useful for initial Flyway production deployments on projects with an existing DB.
     * </p>
     * <p>
     * Be careful when enabling this as it removes the safety net that ensures
     * Flyway does not migrate the wrong database in case of a configuration mistake!
     * </p>
     *
     * @param baselineOnMigrate {@code true} if baseline should be called on migrate for non-empty schemas, {@code false} if not. (default: {@code false})
     */
    public void setBaselineOnMigrate(boolean baselineOnMigrate) {
        this.baselineOnMigrate = baselineOnMigrate;
    }

    /**
     * Sets the version to tag an existing schema with when executing baseline.
     *
     * @param initVersion The version to tag an existing schema with when executing baseline. (default: 1)
     * @deprecated Use setBaselineVersion() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public void setInitVersion(MigrationVersion initVersion) {
        LOG.warn("Flyway.setInitVersion() is deprecated. Use setBaselineVersion() instead. Will be removed in Flyway 4.0.");
        this.baselineVersion = initVersion;
    }

    /**
     * Sets the version to tag an existing schema with when executing baseline.
     *
     * @param initVersion The version to tag an existing schema with when executing baseline. (default: 1)
     * @deprecated Use setBaselineVersion() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public void setInitVersion(String initVersion) {
        LOG.warn("Flyway.setInitVersion() is deprecated. Use setBaselineVersion() instead. Will be removed in Flyway 4.0.");
        this.baselineVersion = MigrationVersion.fromVersion(initVersion);
    }

    /**
     * Sets the description to tag an existing schema with when executing baseline.
     *
     * @param initDescription The description to tag an existing schema with when executing baseline. (default: &lt;&lt; Flyway Baseline &gt;&gt;)
     * @deprecated Use setBaselineDescription() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public void setInitDescription(String initDescription) {
        LOG.warn("Flyway.setInitDescription() is deprecated. Use setBaselineDescription() instead. Will be removed in Flyway 4.0.");
        this.baselineDescription = initDescription;
    }

    /**
     * <p>
     * Whether to automatically call baseline when migrate is executed against a non-empty schema with no metadata table.
     * This schema will then be initialized with the {@code baselineVersion} before executing the migrations.
     * Only migrations above {@code baselineVersion} will then be applied.
     * </p>
     * <p>
     * This is useful for initial Flyway production deployments on projects with an existing DB.
     * </p>
     * <p>
     * Be careful when enabling this as it removes the safety net that ensures
     * Flyway does not migrate the wrong database in case of a configuration mistake!
     * </p>
     *
     * @param initOnMigrate {@code true} if baseline should be called on migrate for non-empty schemas, {@code false} if not. (default: {@code false})
     * @deprecated Use setBaselineOnMigrate() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public void setInitOnMigrate(boolean initOnMigrate) {
        LOG.warn("Flyway.setInitOnMigrate() is deprecated. Use setBaselineOnMigrate() instead. Will be removed in Flyway 4.0.");
        this.baselineOnMigrate = initOnMigrate;
    }

    /**
     * Allows migrations to be run "out of order".
     * <p>If you already have versions 1 and 3 applied, and now a version 2 is found,
     * it will be applied too instead of being ignored.</p>
     *
     * @param outOfOrder {@code true} if outOfOrder migrations should be applied, {@code false} if not. (default: {@code false})
     */
    public void setOutOfOrder(boolean outOfOrder) {
        this.outOfOrder = outOfOrder;
    }

    /**
     * Gets the callbacks for lifecycle notifications.
     *
     * @return The callbacks for lifecycle notifications. An empty array if none. (default: none)
     */
    public FlywayCallback[] getCallbacks() {
        return callbacks;
    }

    /**
     * Set the callbacks for lifecycle notifications.
     *
     * @param callbacks The callbacks for lifecycle notifications. (default: none)
     */
    public void setCallbacks(FlywayCallback... callbacks) {
        this.callbacks = callbacks;
    }

    /**
     * Set the callbacks for lifecycle notifications.
     *
     * @param callbacks The fully qualified class names of the callbacks for lifecycle notifications. (default: none)
     * @deprecated Will be removed in Flyway 4.0. Use setCallbacksAsClassNames(String...) instead.
     */
    @Deprecated
    public void setCallbacks(String... callbacks) {
        LOG.warn("Flyway.setCallbacks(String...) is deprecated and will be removed in Flyway 4.0. Use setCallbacksAsClassNames(String...) instead.");
        List<FlywayCallback> callbackList = ClassUtils.instantiateAll(callbacks, classLoader);
        this.callbacks = callbackList.toArray(new FlywayCallback[callbacks.length]);
    }

    /**
     * Set the callbacks for lifecycle notifications.
     *
     * @param callbacks The fully qualified class names of the callbacks for lifecycle notifications. (default: none)
     */
    public void setCallbacksAsClassNames(String... callbacks) {
        List<FlywayCallback> callbackList = ClassUtils.instantiateAll(callbacks, classLoader);
        this.callbacks = callbackList.toArray(new FlywayCallback[callbacks.length]);
    }

    /**
     * Sets custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply.
     *
     * @param resolvers The custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply. (default: empty list)
     */
    public void setResolvers(MigrationResolver... resolvers) {
        this.resolvers = resolvers;
    }

    /**
     * Sets custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply.
     *
     * @param resolvers The fully qualified class names of the custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply. (default: empty list)
     * @deprecated Will be removed in Flyway 4.0. Use setResolversAsClassNames(String...) instead.
     */
    @Deprecated
    public void setResolvers(String... resolvers) {
        LOG.warn("Flyway.setResolvers(String...) is deprecated and will be removed in Flyway 4.0. Use setResolversAsClassNames(String...) instead.");
        List<MigrationResolver> resolverList = ClassUtils.instantiateAll(resolvers, classLoader);
        this.resolvers = resolverList.toArray(new MigrationResolver[resolvers.length]);
    }

    /**
     * Sets custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply.
     *
     * @param resolvers The fully qualified class names of the custom MigrationResolvers to be used in addition to the built-in ones for resolving Migrations to apply. (default: empty list)
     */
    public void setResolversAsClassNames(String... resolvers) {
        List<MigrationResolver> resolverList = ClassUtils.instantiateAll(resolvers, classLoader);
        this.resolvers = resolverList.toArray(new MigrationResolver[resolvers.length]);
    }

    /**
     * <p>Starts the database migration. All pending migrations will be applied in order.
     * Calling migrate on an up-to-date database has no effect.</p>
     * <img src="http://flywaydb.org/assets/balsamiq/command-migrate.png" alt="migrate">
     *
     * @return The number of successfully applied migrations.
     * @throws FlywayException when the migration failed.
     */
    public int migrate() throws FlywayException {
        return execute(new Command<Integer>() {
            public Integer execute(Connection connectionMetaDataTable, Connection connectionUserObjects, DbSupport dbSupport, Schema[] schemas) {
                MetaDataTable metaDataTable = new MetaDataTableImpl(dbSupport, schemas[0].getTable(table));

                MigrationResolver migrationResolver = createMigrationResolver(dbSupport);
                if (validateOnMigrate) {
                    doValidate(connectionMetaDataTable, connectionUserObjects, migrationResolver, metaDataTable,
                            schemas, true);
                }

                new DbSchemas(connectionMetaDataTable, schemas, metaDataTable).create();

                if (!metaDataTable.hasSchemasMarker() && !metaDataTable.hasBaselineMarker() && !metaDataTable.hasAppliedMigrations()) {
                    List<Schema> nonEmptySchemas = new ArrayList<Schema>();
                    for (Schema schema : schemas) {
                        if (!schema.empty()) {
                            nonEmptySchemas.add(schema);
                        }
                    }

                    if (baselineOnMigrate || nonEmptySchemas.isEmpty()) {
                        if (baselineOnMigrate && !nonEmptySchemas.isEmpty()) {
                            new DbBaseline(connectionMetaDataTable, metaDataTable, baselineVersion, baselineDescription, callbacks).baseline();
                        }
                    } else {
                        if (nonEmptySchemas.size() == 1) {
                            Schema schema = nonEmptySchemas.get(0);
                            //Check whether we only have an empty metadata table in an otherwise empty schema
                            if (schema.allTables().length != 1 || !schema.getTable(table).exists()) {
                                throw new FlywayException("Found non-empty schema " + schema
                                        + " without metadata table! Use baseline()"
                                        + " or set baselineOnMigrate to true to initialize the metadata table.");
                            }
                        } else {
                            throw new FlywayException("Found non-empty schemas "
                                    + StringUtils.collectionToCommaDelimitedString(nonEmptySchemas)
                                    + " without metadata table! Use baseline()"
                                    + " or set baselineOnMigrate to true to initialize the metadata table.");
                        }
                    }
                }

                DbSupport dbSupportUserObjects = DbSupportFactory.createDbSupport(connectionUserObjects, false);
                Schema originalSchemaUserObjects = dbSupportUserObjects.getCurrentSchema();
                boolean schemaChange = !schemas[0].equals(originalSchemaUserObjects);
                if (schemaChange) {
                    dbSupportUserObjects.setCurrentSchema(schemas[0]);
                }

                DbMigrate dbMigrate =
                        new DbMigrate(connectionMetaDataTable, connectionUserObjects, dbSupport, metaDataTable,
                                schemas[0], migrationResolver, target, ignoreFailedFutureMigration, outOfOrder, callbacks);
                try {
                    return dbMigrate.migrate();
                } finally {
                    if (schemaChange) {
                        dbSupportUserObjects.setCurrentSchema(originalSchemaUserObjects);
                    }
                }
            }
        });
    }

    /**
     * <p>Validate applied migration with classpath migrations to detect accidental changes.</p>
     * <img src="http://flywaydb.org/assets/balsamiq/command-validate.png" alt="validate">
     *
     * @throws FlywayException when the validation failed.
     */
    public void validate() throws FlywayException {
        execute(new Command<Void>() {
            public Void execute(Connection connectionMetaDataTable, Connection connectionUserObjects, DbSupport dbSupport, Schema[] schemas) {
                MetaDataTable metaDataTable = new MetaDataTableImpl(dbSupport, schemas[0].getTable(table));
                MigrationResolver migrationResolver = createMigrationResolver(dbSupport);

                doValidate(connectionMetaDataTable, connectionUserObjects, migrationResolver, metaDataTable, schemas,
                        false);
                return null;
            }
        });
    }

    /**
     * Performs the actual validation. All set up must have taken place beforehand.
     *
     * @param connectionMetaDataTable The database connection for the metadata table.
     * @param connectionUserObjects   The database connection for the data.
     * @param migrationResolver       The migration resolver;
     * @param metaDataTable           The metadata table.
     * @param schemas                 The schemas managed by Flyway.
     * @param pendingOrFuture         Whether pending or future migrations are ok.
     */
    private void doValidate(Connection connectionMetaDataTable, Connection connectionUserObjects, MigrationResolver migrationResolver,
                            MetaDataTable metaDataTable, Schema[] schemas, boolean pendingOrFuture) {
        String validationError =
                new DbValidate(connectionMetaDataTable, connectionUserObjects, metaDataTable, migrationResolver,
                        target, outOfOrder, pendingOrFuture, callbacks).validate();

        if (validationError != null) {
            if (cleanOnValidationError) {
                new DbClean(connectionMetaDataTable, metaDataTable, schemas, callbacks).clean();
            } else {
                throw new FlywayException("Validate failed. " + validationError);
            }
        }
    }

    /**
     * <p>Drops all objects (tables, views, procedures, triggers, ...) in the configured schemas.
     * The schemas are cleaned in the order specified by the {@code schemas} property.</p>
     * <img src="http://flywaydb.org/assets/balsamiq/command-clean.png" alt="clean">
     *
     * @throws FlywayException when the clean fails.
     */
    public void clean() {
        execute(new Command<Void>() {
            public Void execute(Connection connectionMetaDataTable, Connection connectionUserObjects, DbSupport dbSupport, Schema[] schemas) {
                MetaDataTableImpl metaDataTable =
                        new MetaDataTableImpl(dbSupport, schemas[0].getTable(table));
                new DbClean(connectionMetaDataTable, metaDataTable, schemas, callbacks).clean();
                return null;
            }
        });
    }

    /**
     * <p>Retrieves the complete information about all the migrations including applied, pending and current migrations with
     * details and status.</p>
     * <img src="http://flywaydb.org/assets/balsamiq/command-info.png" alt="info">
     *
     * @return All migrations sorted by version, oldest first.
     * @throws FlywayException when the info retrieval failed.
     */
    public MigrationInfoService info() {
        return execute(new Command<MigrationInfoService>() {
            public MigrationInfoService execute(Connection connectionMetaDataTable, Connection connectionUserObjects, DbSupport dbSupport, Schema[] schemas) {
                for (FlywayCallback callback : getCallbacks()) {
                    callback.beforeInfo(connectionUserObjects);
                }

                MigrationResolver migrationResolver = createMigrationResolver(dbSupport);
                MetaDataTable metaDataTable = new MetaDataTableImpl(dbSupport, schemas[0].getTable(table));

                MigrationInfoServiceImpl migrationInfoService =
                        new MigrationInfoServiceImpl(migrationResolver, metaDataTable, target, outOfOrder, true);
                migrationInfoService.refresh();

                for (FlywayCallback callback : getCallbacks()) {
                    callback.afterInfo(connectionUserObjects);
                }

                return migrationInfoService;
            }
        });
    }

    /**
     * Baselines an existing database, excluding all migrations up to and including baselineVersion.
     *
     * @throws FlywayException when the schema baselining failed.
     * @deprecated Use baseline() instead. Will be removed in Flyway 4.0.
     */
    @Deprecated
    public void init() throws FlywayException {
        LOG.warn("Flyway.init() is deprecated. Use baseline() instead. Will be removed in Flyway 4.0.");
        baseline();
    }

    /**
     * <p>Baselines an existing database, excluding all migrations up to and including baselineVersion.</p>
     * <p/>
     * <img src="http://flywaydb.org/assets/balsamiq/command-baseline.png" alt="baseline">
     *
     * @throws FlywayException when the schema baselining failed.
     */
    public void baseline() throws FlywayException {
        execute(new Command<Void>() {
            public Void execute(Connection connectionMetaDataTable, Connection connectionUserObjects, DbSupport dbSupport, Schema[] schemas) {
                MetaDataTable metaDataTable = new MetaDataTableImpl(dbSupport, schemas[0].getTable(table));
                new DbSchemas(connectionMetaDataTable, schemas, metaDataTable).create();
                new DbBaseline(connectionMetaDataTable, metaDataTable, baselineVersion, baselineDescription, callbacks).baseline();
                return null;
            }
        });
    }

    /**
     * Repairs the Flyway metadata table. This will perform the following actions:
     * <ul>
     * <li>Remove any failed migrations on databases without DDL transactions (User objects left behind must still be cleaned up manually)</li>
     * <li>Correct wrong checksums</li>
     * </ul>
     * <img src="http://flywaydb.org/assets/balsamiq/command-repair.png" alt="repair">
     *
     * @throws FlywayException when the metadata table repair failed.
     */
    public void repair() throws FlywayException {
        execute(new Command<Void>() {
            public Void execute(Connection connectionMetaDataTable, Connection connectionUserObjects, DbSupport dbSupport, Schema[] schemas) {
                MigrationResolver migrationResolver = createMigrationResolver(dbSupport);
                MetaDataTable metaDataTable = new MetaDataTableImpl(dbSupport, schemas[0].getTable(table));
                new DbRepair(dbSupport, connectionMetaDataTable, migrationResolver, metaDataTable, callbacks).repair();
                return null;
            }
        });
    }

    /**
     * Creates the MigrationResolver.
     *
     * @param dbSupport The database-specific support.
     * @return A new, fully configured, MigrationResolver instance.
     */
    private MigrationResolver createMigrationResolver(DbSupport dbSupport) {
        return new CompositeMigrationResolver(dbSupport, classLoader, locations,
                encoding, sqlMigrationPrefix, sqlMigrationSeparator, sqlMigrationSuffix, createPlaceholderReplacer(),
                resolvers);
    }

    /**
     * @return A new, fully configured, PlaceholderReplacer.
     */
    private PlaceholderReplacer createPlaceholderReplacer() {
        if (placeholderReplacement) {
            return new PlaceholderReplacer(placeholders, placeholderPrefix, placeholderSuffix);
        }
        return PlaceholderReplacer.NO_PLACEHOLDERS;
    }

    /**
     * Configures Flyway with these properties. This overwrites any existing configuration. Property names are
     * documented in the flyway maven plugin.
     * <p/>
     * <p>To use a custom ClassLoader, setClassLoader() must be called prior to calling this method.</p>
     *
     * @param properties Properties used for configuration.
     * @throws FlywayException when the configuration failed.
     */
    @SuppressWarnings("ConstantConditions")
    public void configure(Properties properties) {
        String driverProp = properties.getProperty("flyway.driver");
        String urlProp = properties.getProperty("flyway.url");
        String userProp = properties.getProperty("flyway.user");
        String passwordProp = properties.getProperty("flyway.password");

        if (StringUtils.hasText(urlProp)) {
            setDataSource(new DriverDataSource(classLoader, driverProp, urlProp, userProp, passwordProp));
        } else if (!StringUtils.hasText(urlProp) &&
                (StringUtils.hasText(driverProp) || StringUtils.hasText(userProp) || StringUtils.hasText(passwordProp))) {
            LOG.warn("Discarding INCOMPLETE dataSource configuration! flyway.url must be set.");
        }

        String locationsProp = properties.getProperty("flyway.locations");
        if (locationsProp != null) {
            setLocations(StringUtils.tokenizeToStringArray(locationsProp, ","));
        }
        String placeholderPrefixProp = properties.getProperty("flyway.placeholderPrefix");
        if (placeholderPrefixProp != null) {
            setPlaceholderPrefix(placeholderPrefixProp);
        }
        String placeholderSuffixProp = properties.getProperty("flyway.placeholderSuffix");
        if (placeholderSuffixProp != null) {
            setPlaceholderSuffix(placeholderSuffixProp);
        }
        String sqlMigrationPrefixProp = properties.getProperty("flyway.sqlMigrationPrefix");
        if (sqlMigrationPrefixProp != null) {
            setSqlMigrationPrefix(sqlMigrationPrefixProp);
        }
        String sqlMigrationSeparatorProp = properties.getProperty("flyway.sqlMigrationSeparator");
        if (sqlMigrationSeparatorProp != null) {
            setSqlMigrationSeparator(sqlMigrationSeparatorProp);
        }
        String sqlMigrationSuffixProp = properties.getProperty("flyway.sqlMigrationSuffix");
        if (sqlMigrationSuffixProp != null) {
            setSqlMigrationSuffix(sqlMigrationSuffixProp);
        }
        String encodingProp = properties.getProperty("flyway.encoding");
        if (encodingProp != null) {
            setEncoding(encodingProp);
        }
        String schemasProp = properties.getProperty("flyway.schemas");
        if (schemasProp != null) {
            setSchemas(StringUtils.tokenizeToStringArray(schemasProp, ","));
        }
        String tableProp = properties.getProperty("flyway.table");
        if (tableProp != null) {
            setTable(tableProp);
        }
        String cleanOnValidationErrorProp = properties.getProperty("flyway.cleanOnValidationError");
        if (cleanOnValidationErrorProp != null) {
            setCleanOnValidationError(Boolean.parseBoolean(cleanOnValidationErrorProp));
        }
        String validateOnMigrateProp = properties.getProperty("flyway.validateOnMigrate");
        if (validateOnMigrateProp != null) {
            setValidateOnMigrate(Boolean.parseBoolean(validateOnMigrateProp));
        }
        String initVersionProp = properties.getProperty("flyway.initVersion");
        if (initVersionProp != null) {
            LOG.warn("flyway.initVersion is deprecated and will be removed in Flyway 4.0. Use flyway.baselineVersion instead.");
            setBaselineVersion(MigrationVersion.fromVersion(initVersionProp));
        }
        String initDescriptionProp = properties.getProperty("flyway.initDescription");
        if (initDescriptionProp != null) {
            LOG.warn("flyway.initDescription is deprecated and will be removed in Flyway 4.0. Use flyway.baselineDescription instead.");
            setBaselineDescription(initDescriptionProp);
        }
        String initOnMigrateProp = properties.getProperty("flyway.initOnMigrate");
        if (initOnMigrateProp != null) {
            LOG.warn("flyway.initOnMigrate is deprecated and will be removed in Flyway 4.0. Use flyway.baselineOnMigrate instead.");
            setBaselineOnMigrate(Boolean.parseBoolean(initOnMigrateProp));
        }
        String baselineVersionProp = properties.getProperty("flyway.baselineVersion");
        if (baselineVersionProp != null) {
            setBaselineVersion(MigrationVersion.fromVersion(baselineVersionProp));
        }
        String baselineDescriptionProp = properties.getProperty("flyway.baselineDescription");
        if (baselineDescriptionProp != null) {
            setBaselineDescription(baselineDescriptionProp);
        }
        String baselineOnMigrateProp = properties.getProperty("flyway.baselineOnMigrate");
        if (baselineOnMigrateProp != null) {
            setBaselineOnMigrate(Boolean.parseBoolean(baselineOnMigrateProp));
        }
        String ignoreFailedFutureMigrationProp = properties.getProperty("flyway.ignoreFailedFutureMigration");
        if (ignoreFailedFutureMigrationProp != null) {
            setIgnoreFailedFutureMigration(Boolean.parseBoolean(ignoreFailedFutureMigrationProp));
        }
        String targetProp = properties.getProperty("flyway.target");
        if (targetProp != null) {
            setTarget(MigrationVersion.fromVersion(targetProp));
        }
        String outOfOrderProp = properties.getProperty("flyway.outOfOrder");
        if (outOfOrderProp != null) {
            setOutOfOrder(Boolean.parseBoolean(outOfOrderProp));
        }
        String resolversProp = properties.getProperty("flyway.resolvers");
        if (StringUtils.hasLength(resolversProp)) {
            setResolversAsClassNames(StringUtils.tokenizeToStringArray(resolversProp, ","));
        }
        String callbacksProp = properties.getProperty("flyway.callbacks");
        if (StringUtils.hasLength(callbacksProp)) {
            setCallbacksAsClassNames(StringUtils.tokenizeToStringArray(callbacksProp, ","));
        }

        Map<String, String> placeholdersFromProps = new HashMap<String, String>(placeholders);
        for (Object property : properties.keySet()) {
            String propertyName = (String) property;
            if (propertyName.startsWith(PLACEHOLDERS_PROPERTY_PREFIX)
                    && propertyName.length() > PLACEHOLDERS_PROPERTY_PREFIX.length()) {
                String placeholderName = propertyName.substring(PLACEHOLDERS_PROPERTY_PREFIX.length());
                String placeholderValue = properties.getProperty(propertyName);
                placeholdersFromProps.put(placeholderName, placeholderValue);
            }
        }
        setPlaceholders(placeholdersFromProps);
    }

    /**
     * Executes this command with proper resource handling and cleanup.
     *
     * @param command The command to execute.
     * @param <T>     The type of the result.
     * @return The result of the command.
     */
    /*private -> testing*/ <T> T execute(Command<T> command) {
        T result;

        VersionPrinter.printVersion(classLoader);

        Connection connectionMetaDataTable = null;
        Connection connectionUserObjects = null;

        boolean callbackAutoAdded = false;

        try {
            if (dataSource == null) {
                throw new FlywayException("Unable to connect to the database. Configure the url, user and password!");
            }

            connectionMetaDataTable = JdbcUtils.openConnection(dataSource);
            connectionUserObjects = JdbcUtils.openConnection(dataSource);

            DbSupport dbSupport = DbSupportFactory.createDbSupport(connectionMetaDataTable, !dbConnectionInfoPrinted);
            dbConnectionInfoPrinted = true;
            LOG.debug("DDL Transactions Supported: " + dbSupport.supportsDdlTransactions());

            if (schemaNames.length == 0) {
                Schema currentSchema = dbSupport.getCurrentSchema();
                if (currentSchema == null) {
                    throw new FlywayException("Unable to determine schema for the metadata table." +
                            " Set a default schema for the connection or specify one using the schemas property!");
                }
                setSchemas(currentSchema.getName());
            }

            if (schemaNames.length == 1) {
                LOG.debug("Schema: " + schemaNames[0]);
            } else {
                LOG.debug("Schemas: " + StringUtils.arrayToCommaDelimitedString(schemaNames));
            }

            Schema[] schemas = new Schema[schemaNames.length];
            for (int i = 0; i < schemaNames.length; i++) {
                schemas[i] = dbSupport.getSchema(schemaNames[i]);
            }

            if (callbacks.length == 0) {
                setCallbacks(new SqlScriptFlywayCallback(dbSupport, classLoader, locations, createPlaceholderReplacer(),
                        encoding, sqlMigrationSuffix));
                callbackAutoAdded = true;
            }

            result = command.execute(connectionMetaDataTable, connectionUserObjects, dbSupport, schemas);
        } finally {
            if (callbackAutoAdded) {
                setCallbacksAsClassNames();
            }

            JdbcUtils.closeConnection(connectionUserObjects);
            JdbcUtils.closeConnection(connectionMetaDataTable);

            if ((dataSource instanceof DriverDataSource) && createdDataSource) {
                ((DriverDataSource) dataSource).close();
            }
        }
        return result;
    }

    /**
     * A Flyway command that can be executed.
     *
     * @param <T> The result type of the command.
     */
    /*private -> testing*/ interface Command<T> {
        /**
         * Execute the operation.
         *
         * @param connectionMetaDataTable The database connection for the metadata table changes.
         * @param connectionUserObjects   The database connection for user object changes.
         * @param dbSupport               The database-specific support for these connections.
         * @param schemas                 The schemas managed by Flyway.
         * @return The result of the operation.
         */
        T execute(Connection connectionMetaDataTable, Connection connectionUserObjects, DbSupport dbSupport, Schema[] schemas);
    }
}
