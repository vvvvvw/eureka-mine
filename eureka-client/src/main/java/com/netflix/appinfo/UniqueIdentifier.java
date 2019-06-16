package com.netflix.appinfo;

/**
 * Generally indicates the unique identifier of a {@link com.netflix.appinfo.DataCenterInfo}, if applicable.
 *
 * //表示DataCenterInfo的唯一标识符
 *
 * @author rthomas@atlassian.com
 */
public interface UniqueIdentifier {
    String getId();
}
