# Docker support

The files in this directory allow you to build a docker image.  The data coordinator assembly must be
copied to `coordinator-assembly.jar` in this directory before building.

This is designed to be used as a base image to inherit from when building a secondary watcher
for a specific secondary type.  In that image, you need to put your `Secondary` plugin jar into
the plugins subdirectory and include a `secondary.conf` in `/srv/secondary-watcher` that gets
included in the secondary watcher config.

## Required Runtime Variables

* `DATA_COORDINATOR_DB_HOST` - Data Coordinator DB hostname
* `DATA_COORDINATOR_DB_PASSWORD_LINE` - Full line of config for Data Coordinator DB password.  Designed to be either `password = "foo"` or `include /path/to/file`.

## Optional Runtime Variables

See the Dockerfile for defaults.

* `ARK_HOST` - The IP address of the host of the docker container, used for service advertisements.
* `ENABLE_GRAPHITE` - Should various metrics information be reported to graphite
* `GRAPHITE_HOST` - The hostname or IP of the graphite server, if enabled
* `GRAPHITE_PORT` - The port number for the graphite server, if enabled
* `JAVA_XMX` - Sets the -Xmx and -Xms parameters to control the JVM heap size
* `LOG_METRICS` - Should various metrics information be logged to the log
* `DATA_COORDINATOR_DB_NAME` - Data Coordinator DB database name
* `DATA_COORDINATOR_DB_PORT` - Data Coordinator DB port number
* `DATA_COORDINATOR_DB_USER` - Data Coordinator DB user name