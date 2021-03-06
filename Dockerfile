FROM govukpay/openjdk:8-jre-alpine

RUN apk update
RUN apk upgrade

RUN apk add bash

ENV PORT 8080
ENV ADMIN_PORT 8081

EXPOSE 8080
EXPOSE 8081

WORKDIR /app

ADD target/*.yaml .
ADD target/pay-*-allinone.jar .
ADD docker-startup.sh .
ADD docker-startup-with-db-migration.sh .
ADD run-with-chamber.sh .

CMD bash ./docker-startup.sh
