FROM tomcat:8

ENV IMAGE_JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64/jre

EXPOSE ${pass.doi.service.port}

ADD  target/pass-doi-service.war /usr/local/tomcat/webapps/

CMD ["catalina.sh", "run"]
