plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(project(":metadata"))
    implementation(project(":image"))
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
    implementation(files("../Libraries/eventbus.jar"))
    implementation(libs.hsqldb)
}
