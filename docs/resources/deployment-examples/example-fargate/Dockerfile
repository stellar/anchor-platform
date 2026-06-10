FROM ubuntu:24.04
RUN mkdir /config_files
ENV STELLAR_ANCHOR_CONFIG=file:/config/anchor-config.yaml
ENV REFERENCE_SERVER_CONFIG_ENV=file:/config/reference-config.yaml
ADD ./scripts/copy_config.sh /copy_config.sh 
RUN chmod +x /copy_config.sh
ADD ./scripts/sep.sh /config_files/sep.sh 
RUN chmod +x /config_files/sep.sh
ADD ./scripts/stellar_observer.sh /config_files/stellar_observer.sh
RUN chmod +x /config_files/stellar_observer.sh
RUN echo $(ls -1 /config)
ADD ./anchor_config.yaml /config_files/anchor_config.yaml
ADD ./reference_config.yaml /config_files/reference_config.yaml
ADD ./stellar_wks.toml /config_files/stellar_wks.toml 
RUN echo $(ls -1 /config_files)
RUN echo $(ls -1 /)
WORKDIR "/docs/resources/deployment-examples/example-fargate"
ENTRYPOINT ["/copy_config.sh"]
