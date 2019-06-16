package com.netflix.discovery;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Provider;

import com.google.inject.Inject;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClient;
import com.netflix.discovery.shared.transport.jersey.TransportClientFactories;
import com.netflix.eventbus.spi.EventBus;

/**
 * <T> The type for client supplied filters (supports jersey1 and jersey2)
 */
//提供客户端过滤器
public abstract class AbstractDiscoveryClientOptionalArgs<T> {
    //生成健康检查回调的工厂
    //健康检查回调接口，目前已经废弃，使用 HealthCheckHandler 替代，你可以不关注该参数
    Provider<HealthCheckCallback> healthCheckCallbackProvider;

    //生成健康检查处理器的工厂
    Provider<HealthCheckHandler> healthCheckHandlerProvider;

    //向 Eureka-Server 注册之前的处理器
    PreRegistrationHandler preRegistrationHandler;

    //Jersey 过滤器集合
    //这里声明泛型 <T> 的原因，Jersey 1.X 和 Jersey 2.X 的过滤器接口不同，通过泛型来支持
    Collection<T> additionalFilters;

    //Jersey 客户端
    EurekaJerseyClient eurekaJerseyClient;

    //生成 Jersey 客户端的工厂
    TransportClientFactory transportClientFactory;

    //生成 Jersey 客户端的工厂的工厂
    TransportClientFactories transportClientFactories;

    //Eureka 事件监听器集合
    private Set<EurekaEventListener> eventListeners;

    @Inject(optional = true)
    public void setEventListeners(Set<EurekaEventListener> listeners) {
        if (eventListeners == null) {
            eventListeners = new HashSet<>();
        }
        eventListeners.addAll(listeners);
    }
    
    @Inject(optional = true)
    public void setEventBus(final EventBus eventBus) {
        if (eventListeners == null) {
            eventListeners = new HashSet<>();
        }
        
        eventListeners.add(new EurekaEventListener() {
            @Override
            public void onEvent(EurekaEvent event) {
                eventBus.publish(event);
            }
        });
    }

    @Inject(optional = true) 
    public void setHealthCheckCallbackProvider(Provider<HealthCheckCallback> healthCheckCallbackProvider) {
        this.healthCheckCallbackProvider = healthCheckCallbackProvider;
    }

    @Inject(optional = true) 
    public void setHealthCheckHandlerProvider(Provider<HealthCheckHandler> healthCheckHandlerProvider) {
        this.healthCheckHandlerProvider = healthCheckHandlerProvider;
    }

    @Inject(optional = true)
    public void setPreRegistrationHandler(PreRegistrationHandler preRegistrationHandler) {
        this.preRegistrationHandler = preRegistrationHandler;
    }


    @Inject(optional = true) 
    public void setAdditionalFilters(Collection<T> additionalFilters) {
        this.additionalFilters = additionalFilters;
    }

    @Inject(optional = true) 
    public void setEurekaJerseyClient(EurekaJerseyClient eurekaJerseyClient) {
        this.eurekaJerseyClient = eurekaJerseyClient;
    }
    
    Set<EurekaEventListener> getEventListeners() {
        return eventListeners == null ? Collections.<EurekaEventListener>emptySet() : eventListeners;
    }
    
    public TransportClientFactories getTransportClientFactories() {
        return transportClientFactories;
    }

    @Inject(optional = true)
    public void setTransportClientFactories(TransportClientFactories transportClientFactories) {
        this.transportClientFactories = transportClientFactories;
    }
}