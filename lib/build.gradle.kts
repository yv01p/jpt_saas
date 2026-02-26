plugins {
    id("jpt.java-conventions")
}

dependencies {
    // Sibling modules
    implementation(project(":jpt-api"))

    // JAXB (Jakarta)
    implementation(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)

    // NetBeans Lookup (org.openide.util.*)
    implementation(libs.netbeans.lookup)

    // Legacy jars from Libraries/
    implementation(files("../Libraries/beansbinding.jar"))      // org.jdesktop.beansbinding, org.jdesktop.observablecollections
    implementation(files("../Libraries/swingx-core.jar"))       // org.jdesktop.swingx
    implementation(files("../Libraries/eventbus.jar"))          // org.bushe.swing
    implementation(files("../Libraries/lucene-core.jar"))       // org.apache.lucene

    // Resources module
    implementation(project(":resources"))

    // JUnit 5 (Jupiter)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
