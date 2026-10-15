@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Maven Wrapper Startup Batch Script
@REM ----------------------------------------------------------------------------

@echo off
setlocal enabledelayedexpansion

set "MVNW_DIR=%~dp0.mvn\wrapper"
set "MVNW_JAR=%MVNW_DIR%\maven-wrapper.jar"
set "MVNW_PROP=%MVNW_DIR%\maven-wrapper.properties"

if not exist "%MVNW_JAR%" (
    echo Maven Wrapper jar not found: %MVNW_JAR%
    echo Please ensure the .mvn\wrapper\maven-wrapper.jar file exists.
    exit /b 1
)

set "JAVA_HOME=%JAVA_HOME%"
if not defined JAVA_HOME (
    echo JAVA_HOME is not set. Attempting to find Java...
    for %%i in (java.exe) do set "JAVACMD=%%~$PATH:i"
    if not defined JAVACMD (
        echo Java not found in PATH. Please set JAVA_HOME or add Java to PATH.
        exit /b 1
    )
) else (
    set "JAVACMD=%JAVA_HOME%\bin\java.exe"
)

set "MAVEN_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=256m"

"%JAVACMD%" -cp "%MVNW_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*

endlocal
