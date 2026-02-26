plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(project(":resources"))
    implementation(project(":image"))
    implementation(project(":kml"))
    implementation(libs.metadata.extractor)
    implementation(libs.xmpcore)
    implementation(libs.jaxb.runtime)
    implementation(files("../Libraries/eventbus.jar"))
    implementation(files("../Libraries/mapdb.jar"))
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
}
