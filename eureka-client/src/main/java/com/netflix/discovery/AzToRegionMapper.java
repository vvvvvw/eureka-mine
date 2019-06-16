package com.netflix.discovery;

/**
 * An interface that contains a contract of mapping availability zone to region mapping. An implementation will always
 * know before hand which zone to region mapping will be queried from the mapper, this will aid caching of this
 * information before hand.
 * 接口，包含了可用区域到region的映射。通过实现应该能查询可用区域到region的映射
 * @author Nitesh Kant
 */
public interface AzToRegionMapper {

    /**
     * Returns the region for the passed availability zone.
     *
     * @param availabilityZone Availability zone for which the region is to be retrieved.
     * 返回传递的可用区域对应的region
     * @return The region for the passed zone.
     */
    String getRegionForAvailabilityZone(String availabilityZone);

    /**
     * Update the regions that this mapper knows about.
     *
     * @param regionsToFetch Regions to fetch. This should be the super set of all regions that this mapper should know.
     */
    //更新Regions
    void setRegionsToFetch(String[] regionsToFetch);

    /**
     * 如果zone到region的映射基于外部来源，更新
     * Updates the mappings it has if they depend on an external source.
     */
    void refreshMapping();
}
