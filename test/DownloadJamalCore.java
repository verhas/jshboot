/open ../JshBoot.java

JshBoot.localRepo().
    maven().groupId("com.javax0.jamal").version("1.2.0").
    artifactId("jamal-core").
    artifactId("jamal-engine").
    artifactId("jamal-cmd").
    artifactId("jamal-api").
    artifactId("jamal-tools").
    execute("javax0.jamal.cmd.JamalMain");

/exit