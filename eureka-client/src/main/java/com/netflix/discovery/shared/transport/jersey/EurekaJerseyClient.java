package com.netflix.discovery.shared.transport.jersey;

import com.sun.jersey.client.apache4.ApacheHttpClient4;

/**
 * @author David Liu
 */
//访问 Jersey Server 的  Jersey 客户端封装
public interface EurekaJerseyClient {

    //基于 Apache HttpClient4 实现的 Jersey Client
    ApacheHttpClient4 getClient();

    /**
     * Clean up resources.
     */
    void destroyResources();
}
