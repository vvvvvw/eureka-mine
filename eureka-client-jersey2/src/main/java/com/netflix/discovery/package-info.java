package com.netflix.discovery;

/*
Jersey 是 JAX-RS（JSR311）开源参考实现，用于构建 RESTful Web Service。

Eureka-Server 使用 Jersey Server 创建 RESTful Server 。
Eureka-Client 使用 Jersey Client 请求 Eureka-Server 。
Jersey 目前有 1.x 和 2.x 版本，默认情况下，Eureka 使用 1.x 版本。从官方文档上来看，2.x 版本由社区实现，Netflix 自己暂未使用。

FROM eureka-client-jersey2 README
Please note that this jersey2 compatible Eureka client (eureka-client-jersey2) is created and maintained by the community. Netflix does not currently use this library internally. */
