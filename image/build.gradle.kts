plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(project(":resources"))
    implementation(libs.metadata.extractor)
    implementation(files("../Libraries/eventbus.jar"))
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
}
