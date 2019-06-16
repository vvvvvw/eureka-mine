package com.netflix.discovery.shared.transport.jersey;

import java.util.Collection;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.sun.jersey.api.client.filter.ClientFilter;

/**
 * 生成 Jersey 客户端工厂的工厂接口
 * @param <F> 过滤器泛型
 */
public interface TransportClientFactories<F> {
    
    @Deprecated
    public TransportClientFactory newTransportClientFactory(final Collection<F> additionalFilters,
                                                                   final EurekaJerseyClient providedJerseyClient);

    public TransportClientFactory newTransportClientFactory(final EurekaClientConfig clientConfig,
                                                                   final Collection<F> additionalFilters,
                                                                   final InstanceInfo myInstanceInfo);
}