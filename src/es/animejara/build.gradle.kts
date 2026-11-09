import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeJara"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "es"
        baseUrl = "https://animejara.com"
    }
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:streamtapeextractor"))
}
