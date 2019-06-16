package com.netflix.appinfo;

/**
 * This class holds metadata information related to eureka client auth with the eureka server
 */
//本类中包含有eureka客户端的元认证数据
public class EurekaClientIdentity extends AbstractEurekaIdentity {
    public static final String DEFAULT_CLIENT_NAME = "DefaultClient";

    private final String clientVersion = "1.4";
    private final String id;
    private final String clientName;

    public EurekaClientIdentity(String id) {
        this(id, DEFAULT_CLIENT_NAME);
    }
    
    public EurekaClientIdentity(String id, String clientName) {
        this.id = id;
        this.clientName = clientName;
    }

    @Override
    public String getName() {
        return clientName;
    }

    @Override
    public String getVersion() {
        return clientVersion;
    }

    @Override
    public String getId() {
        return id;
    }
}
