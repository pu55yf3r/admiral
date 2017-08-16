/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.compute.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.Test;

import com.vmware.admiral.compute.container.ComputeBaseTest;
import com.vmware.admiral.compute.profile.ComputeProfileService.ComputeProfile;
import com.vmware.admiral.compute.profile.NetworkProfileService.NetworkProfile;
import com.vmware.admiral.compute.profile.ProfileService.ProfileState;
import com.vmware.admiral.compute.profile.ProfileService.ProfileStateExpanded;
import com.vmware.admiral.compute.profile.StorageProfileService.StorageProfile;
import com.vmware.photon.controller.model.constants.PhotonModelConstants.EndpointType;
import com.vmware.photon.controller.model.resources.NetworkService;
import com.vmware.photon.controller.model.resources.NetworkService.NetworkState;
import com.vmware.photon.controller.model.resources.SubnetService;
import com.vmware.photon.controller.model.resources.SubnetService.SubnetState;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.UriUtils;

/**
 * Tests for the {@link ProfileService} class.
 */
public class ProfileServiceTest extends ComputeBaseTest {
    @Test
    public void testAwsDefault() throws Throwable {
        String awsProfileLink = UriUtils.buildUriPath(ProfileService.FACTORY_LINK,
                EndpointType.aws.name());
        waitForServiceAvailability(awsProfileLink);

        ProfileStateExpanded profile = getDocument(ProfileStateExpanded.class, awsProfileLink,
                UriUtils.URI_PARAM_ODATA_EXPAND,
                ServiceDocumentQueryResult.FIELD_NAME_DOCUMENT_LINKS);

        assertNotNull(profile);
        assertNotNull(profile.name);
        assertEquals(EndpointType.aws.name(), profile.endpointType);
        assertNotNull(profile.computeProfile);
        assertEquals("t2.micro", profile.computeProfile.instanceTypeMapping.get("small").instanceType);
        assertEquals("ami-2f575749", profile.computeProfile
                .imageMapping.get("coreos")
                .imageByRegion.get("eu-west-1"));
    }

    @Test
    public void testVsphereDefault() throws Throwable {
        String awsProfileLink = UriUtils.buildUriPath(ProfileService.FACTORY_LINK,
                EndpointType.vsphere.name());
        waitForServiceAvailability(awsProfileLink);

        ProfileStateExpanded profile = getDocument(ProfileStateExpanded.class, awsProfileLink,
                UriUtils.URI_PARAM_ODATA_EXPAND,
                ServiceDocumentQueryResult.FIELD_NAME_DOCUMENT_LINKS);

        assertNotNull(profile);
        assertNotNull(profile.name);
        assertEquals(EndpointType.vsphere.name(), profile.endpointType);
        assertNotNull(profile.computeProfile);
        assertNull(profile.computeProfile.instanceTypeMapping.get("small").instanceType);
        assertEquals(1, profile.computeProfile.instanceTypeMapping.get("small").cpuCount);
        assertEquals(1024, profile.computeProfile.instanceTypeMapping.get("small").memoryMb);
        assertEquals(
                "https://stable.release.core-os.net/amd64-usr/current/coreos_production_vmware_ova.ova",
                profile.computeProfile.imageMapping.get("coreos").image);
    }

    @Test
    public void testAzureDefault() throws Throwable {
        String azureProfileLink = UriUtils.buildUriPath(ProfileService.FACTORY_LINK,
                EndpointType.azure.name());
        waitForServiceAvailability(azureProfileLink);

        ProfileStateExpanded profile = getDocument(ProfileStateExpanded.class, azureProfileLink,
                UriUtils.URI_PARAM_ODATA_EXPAND,
                ServiceDocumentQueryResult.FIELD_NAME_DOCUMENT_LINKS);

        assertNotNull(profile);
        assertNotNull(profile.name);
        assertEquals(EndpointType.azure.name(), profile.endpointType);
        assertNotNull(profile.computeProfile);
        assertNotNull(profile.storageProfile);
        assertEquals("Basic_A2", profile.computeProfile.instanceTypeMapping.get("large").instanceType);
        assertEquals(2, profile.storageProfile.storageItems.stream().filter(storageItem ->
                        storageItem.defaultItem).collect(Collectors.toList()).get(0)
                .diskProperties.size());
    }

    @Test
    public void testExpanded() throws Throwable {
        ComputeProfile compute = createComputeProfile();
        StorageProfile storage = createStorageProfile();
        NetworkProfile networkProfile = createNetworkProfile();

        ProfileState profile = createProfile(compute, storage, networkProfile);

        ProfileState retrievedProfile = getDocument(ProfileState.class, profile.documentSelfLink);
        assertEquals(compute.documentSelfLink, retrievedProfile.computeProfileLink);
        assertEquals(storage.documentSelfLink, retrievedProfile.storageProfileLink);
        assertEquals(networkProfile.documentSelfLink, retrievedProfile.networkProfileLink);

        ProfileStateExpanded retrievedExpandedProfile = getDocument(ProfileStateExpanded.class,
                profile.documentSelfLink,
                UriUtils.URI_PARAM_ODATA_EXPAND,
                ServiceDocumentQueryResult.FIELD_NAME_DOCUMENT_LINKS);
        assertEquals(compute.documentSelfLink, retrievedExpandedProfile.computeProfileLink);
        assertEquals(storage.documentSelfLink, retrievedExpandedProfile.storageProfileLink);
        assertEquals(networkProfile.documentSelfLink, retrievedExpandedProfile.networkProfileLink);
        assertEquals(compute.documentSelfLink, retrievedExpandedProfile.computeProfile.documentSelfLink);
        assertEquals(storage.documentSelfLink, retrievedExpandedProfile.storageProfile.documentSelfLink);
        assertEquals(networkProfile.documentSelfLink, retrievedExpandedProfile.networkProfile.documentSelfLink);
        assertEquals(1, retrievedExpandedProfile.networkProfile.subnetStates.size());
    }

    @Test
    public void testPut() throws Throwable {
        ProfileState retrievedProfile = createProfile();
        ComputeProfile retrievedComputeProfile = getDocument(ComputeProfile.class, retrievedProfile.computeProfileLink);

        // add more props
        InstanceTypeDescription newItd = new InstanceTypeDescription();
        newItd.name = "instance type 2";
        newItd.instanceType = "vsphere-it-2";

        ComputeImageDescription newCid = new ComputeImageDescription();
        newCid.name = "image 2";
        newCid.image = "image-url-2";

        retrievedComputeProfile.instanceTypeMapping.put(newItd.name, newItd);
        retrievedComputeProfile.imageMapping.put(newCid.name, newCid);
        ComputeProfile updatedComputeProfile = doPut(retrievedComputeProfile);
        assertEquals(updatedComputeProfile.instanceTypeMapping.size(), 2);
        assertEquals(updatedComputeProfile.imageMapping.size(), 2);

        retrievedProfile.name = "test profile updated";
        ProfileState updatedProfile = doPut(retrievedProfile);
        assertEquals(updatedProfile.name, retrievedProfile.name);
    }

    @Test(expected = LocalizableValidationException.class)
    public void testMissingEndpointParams() throws Throwable {
        ProfileState profile = new ProfileState();
        profile.name = "test profile";
        profile.computeProfileLink = "test-link";
        profile.storageProfileLink = "test-link";
        profile.networkProfileLink = "test-link";
        doPost(profile, ProfileService.FACTORY_LINK);
    }

    @Test(expected = LocalizableValidationException.class)
    public void testBothEndpointLinkAndTypeParams() throws Throwable {
        ProfileState profile = new ProfileState();
        profile.name = "test profile";
        profile.computeProfileLink = "test-link";
        profile.storageProfileLink = "test-link";
        profile.networkProfileLink = "test-link";
        profile.endpointLink = "test-link";
        profile.endpointType = EndpointType.aws.name();
        doPost(profile, ProfileService.FACTORY_LINK);
    }

    private ProfileState createProfile() throws Throwable {
        ComputeProfile computeProfile = createComputeProfile();
        StorageProfile storageProfile = createStorageProfile();
        NetworkProfile networkProfile = createNetworkProfile();

        return createProfile(computeProfile, storageProfile, networkProfile);
    }

    private ProfileState createProfile(
            ComputeProfile compute, StorageProfile storage, NetworkProfile networkProfile) throws Throwable {
        ProfileState profile = new ProfileState();
        profile.name = "test profile";
        profile.endpointType = EndpointType.vsphere.name();
        profile.computeProfileLink = compute.documentSelfLink;
        profile.storageProfileLink = storage.documentSelfLink;
        profile.networkProfileLink = networkProfile.documentSelfLink;

        return doPost(profile, ProfileService.FACTORY_LINK);
    }

    private ComputeProfile createComputeProfile() throws Throwable {
        InstanceTypeDescription itd = new InstanceTypeDescription();
        itd.name = "instance type 1";
        itd.instanceType = "vsphere-it";

        ComputeImageDescription cid = new ComputeImageDescription();
        cid.name = "image 1";
        cid.image = "image-url";

        ComputeProfile compute = new ComputeProfile();
        compute.instanceTypeMapping = Collections.singletonMap(itd.name, itd);
        compute.imageMapping = Collections.singletonMap(cid.name, cid);
        return doPost(compute, ComputeProfileService.FACTORY_LINK);
    }

    private StorageProfile createStorageProfile() throws Throwable {
        StorageProfile storage = new StorageProfile();
        return doPost(storage, StorageProfileService.FACTORY_LINK);
    }

    private NetworkProfile createNetworkProfile() throws Throwable {
        NetworkState networkState = new NetworkState();
        networkState.endpointLink = UUID.randomUUID().toString();
        networkState.resourcePoolLink = UUID.randomUUID().toString();
        networkState.regionId = UUID.randomUUID().toString();
        networkState.instanceAdapterReference = new URI("/");
        networkState.subnetCIDR = "0.0.0.0/24";
        networkState = doPost(networkState, NetworkService.FACTORY_LINK);

        SubnetState subnetState = new SubnetState();
        subnetState.subnetCIDR = "0.0.0.0/24";
        subnetState.networkLink = networkState.documentSelfLink;
        subnetState = doPost(subnetState, SubnetService.FACTORY_LINK);

        NetworkProfile networkProfile = new NetworkProfile();
        networkProfile.isolationNetworkLink = networkState.documentSelfLink;
        networkProfile.isolatedSubnetCIDRPrefix = 28;
        networkProfile.subnetLinks = Arrays.asList(subnetState.documentSelfLink);
        return doPost(networkProfile, NetworkProfileService.FACTORY_LINK);
    }
}
