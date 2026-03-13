package org.jphototagger.api.security;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.jphototagger.api.repository.ShareLookupRepository;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

/**
 * ArchUnit test verifying that only ShareLookupRepository's constructor takes a DataSource
 * argument among classes outside of config and repository packages.
 *
 * <p>This enforces SA-F1: the BYPASSRLS share_reader DataSource is encapsulated
 * within ShareLookupRepository and cannot be accidentally injected elsewhere.
 */
class ShareReaderArchTest {

    @Test
    void shareReaderDataSource_onlyUsedByShareLookupRepository() {
        var classes = new ClassFileImporter().importPackages("org.jphototagger.api");

        // No class outside of config/repository packages should call the ShareLookupRepository
        // constructor that takes a DataSource — ensuring only ShareReaderDataSourceConfig
        // (in config package) instantiates ShareLookupRepository with a DataSource.
        ArchRuleDefinition.noClasses()
            .that()
            .resideOutsideOfPackages(
                "org.jphototagger.api.config..",
                "org.jphototagger.api.repository..")
            .should()
            .callConstructor(ShareLookupRepository.class, DataSource.class)
            .check(classes);
    }
}
