cd %~dp0
start /B java -Xmx512m -jar %~dp0org.leo.traceroute.jar --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED