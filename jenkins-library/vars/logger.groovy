// vars/logger.groovy
// Color-coded structured logging used by every stage of the shared pipeline.
// Usage from a Jenkinsfile or another vars/*.groovy file:

def color(String code, String message) {
    return "\u001B[${code}m${message}\u001B[0m"
}

def logInfo(String message) {
    echo color('1;34', "INFO: ${message}")
}

def logSuccess(String message) {
    echo color('1;32', "SUCCESS: ${message}")
}

def logWarning(String message) {
    echo color('1;33', "WARNING: ${message}")
}

def logError(String message) {
    echo color('1;31', "ERROR: ${message}")
}

def stageHeader(String name) {
    echo color('1;36', """
============================================================
 STAGE STARTED: ${name}
============================================================
""")
}
