#!/bin/bash
# Maven wrapper - 修复环境变量问题
java -classpath "E:/apache-maven-3.9.6/boot/plexus-classworlds-2.7.0.jar" \
  -Dclassworlds.conf="E:/apache-maven-3.9.6/bin/m2.conf" \
  -Dmaven.home="E:/apache-maven-3.9.6" \
  -Dmaven.multiModuleProjectDirectory="$(pwd)" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
