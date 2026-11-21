FROM apache/kafka:3.9.1
USER root
RUN apk add --no-cache su-exec
COPY deploy/kafka/start.sh /opt/cabwatch-start.sh
RUN chmod 755 /opt/cabwatch-start.sh
ENTRYPOINT ["/opt/cabwatch-start.sh"]
