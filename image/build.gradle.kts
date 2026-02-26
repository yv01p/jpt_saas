plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(libs.metadata.extractor)
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
}
