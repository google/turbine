package(default_visibility = ["//java/com/google/turbine:__subpackages__"])

java_library(
    name = "zip",
    srcs = ["Zip.java"],
    deps = ["@guava//jar"],
)

java_binary(
    name = "Zip",
    main_class = "com.google.turbine.zip.Zip",
    runtime_deps = [":zip"],
)
