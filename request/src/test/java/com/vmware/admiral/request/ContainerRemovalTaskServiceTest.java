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

package com.vmware.admiral.request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.vmware.admiral.adapter.common.ContainerOperationType;
import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.CompositeComponentFactoryService;
import com.vmware.admiral.compute.container.CompositeComponentService.CompositeComponent;
import com.vmware.admiral.compute.container.CompositeDescriptionService.CompositeDescription;
import com.vmware.admiral.compute.container.ContainerDescriptionService;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ContainerFactoryService;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.compute.container.ContainerService.ContainerState.PowerState;
import com.vmware.admiral.compute.container.GroupResourcePlacementService.GroupResourcePlacementState;
import com.vmware.admiral.compute.container.HostPortProfileService;
import com.vmware.admiral.request.ContainerRemovalTaskService.ContainerRemovalTaskState;
import com.vmware.admiral.request.RequestBrokerService.RequestBrokerState;
import com.vmware.admiral.request.util.TestRequestStateFactory;
import com.vmware.admiral.request.utils.RequestUtils;
import com.vmware.admiral.service.test.MockDockerAdapterService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.xenon.common.Service.Action;
import com.vmware.xenon.common.UriUtils;

public class ContainerRemovalTaskServiceTest extends RequestBaseTest {

    private RequestBrokerState request;

    @Before
    @Override
    public void setUp() throws Throwable {
        super.setUp();
        request = TestRequestStateFactory.createRequestState();
        request.resourceDescriptionLink = containerDesc.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceCount = 2;
    }

    @Test
    public void testContainerRemovalResourceOperationCycle() throws Throwable {
        URI uri = UriUtils.buildUri(host, containerDesc.documentSelfLink);
        doOperation(containerDesc, uri, false, Action.PATCH);

        request = startRequest(request);
        waitForRequestToComplete(request);

        request = getDocument(RequestBrokerState.class, request.documentSelfLink);
        assertNotNull(request);

        // verify the resources are created as expected:
        assertEquals(request.resourceCount, request.resourceLinks.size());
        Collection<String> containerStateLinks = findResourceLinks(ContainerState.class,
                request.resourceLinks);

        assertEquals(request.resourceCount, containerStateLinks.size());
        // verify the placements has been reserved:
        GroupResourcePlacementState groupResourcePlacement = getDocument(GroupResourcePlacementState.class,
                request.groupResourcePlacementLink);
        assertNotNull(groupResourcePlacement);
        assertEquals(groupResourcePlacement.allocatedInstancesCount, request.resourceCount);

        waitFor(() -> {
            ComputeState computeState = getDocument(ComputeState.class,
                    computeHost.documentSelfLink);
            String containers = computeState.customProperties == null ? null
                    : computeState.customProperties
                            .get(ContainerHostService.NUMBER_OF_CONTAINERS_PER_HOST_PROP_NAME);
            return "2".equals(containers);
        });

        RequestBrokerState day2RemovalRequest = new RequestBrokerState();
        day2RemovalRequest.documentSelfLink = extractId(request.documentSelfLink) + "-removal";
        day2RemovalRequest.resourceType = request.resourceType;
        day2RemovalRequest.resourceLinks = request.resourceLinks;
        day2RemovalRequest.operation = ContainerOperationType.DELETE.id;

        day2RemovalRequest = startRequest(day2RemovalRequest);

        String containerRemovalTaskLink = UriUtils.buildUriPath(
                ContainerRemovalTaskFactoryService.SELF_LINK,
                extractId(day2RemovalRequest.documentSelfLink));
        waitForTaskSuccess(containerRemovalTaskLink, ContainerRemovalTaskState.class);

        waitForRequestToComplete(day2RemovalRequest);

        // verify the resources have been removed:
        containerStateLinks = findResourceLinks(ContainerState.class, request.resourceLinks);
        assertTrue(containerStateLinks.isEmpty());

        // verified the placements have been released:
        groupResourcePlacement = getDocument(GroupResourcePlacementState.class,
                request.groupResourcePlacementLink);
        assertNotNull(groupResourcePlacement);
        assertEquals(groupResourcePlacement.allocatedInstancesCount, 0);

        // verify that the containers where removed from the docker mock
        Map<String, String> containerRefsByIds = MockDockerAdapterService
                .getContainerIdsWithContainerReferences();
        for (String containerRef : containerRefsByIds.values()) {
            for (String containerLink : containerStateLinks) {
                if (containerRef.endsWith(containerLink)) {
                    fail("Container State not removed with link: " + containerLink);
                }
            }
        }

        // verify that the containers count of the host is updated
        waitFor(() -> {
            ComputeState computeState = getDocument(ComputeState.class,
                    computeHost.documentSelfLink);
            String containers = computeState.customProperties == null ? null
                    : computeState.customProperties
                            .get(ContainerHostService.NUMBER_OF_CONTAINERS_PER_HOST_PROP_NAME);
            host.log("Container count waiting for 0 is currently: %s", containers);
            return "0".equals(containers);
        });
    }

    @Test
    public void testRemoveAllocatedOnlyContainers() throws Throwable {
        request.customProperties = new HashMap<>();
        request.customProperties.put(RequestUtils.FIELD_NAME_ALLOCATION_REQUEST,
                Boolean.TRUE.toString());
        request = startRequest(request);
        waitForRequestToComplete(request);

        request = getDocument(RequestBrokerState.class, request.documentSelfLink);
        assertNotNull(request);

        // verify the resources are created as expected:
        assertEquals(request.resourceCount, request.resourceLinks.size());
        Collection<String> containerStateLinks = findResourceLinks(ContainerState.class,
                request.resourceLinks);
        assertEquals(request.resourceCount, containerStateLinks.size());
        // verify containers are not provisioned:
        for (String containerSelfLink : containerStateLinks) {
            ContainerState container = getDocument(ContainerState.class, containerSelfLink);
            assertNotNull(container);
            assertNull(container.id);
            waitForContainerPowerState(PowerState.PROVISIONING, container.documentSelfLink);
        }

        // verify the placements has been reserved:
        GroupResourcePlacementState groupResourcePlacement = getDocument(GroupResourcePlacementState.class,
                request.groupResourcePlacementLink);
        assertNotNull(groupResourcePlacement);
        assertEquals(groupResourcePlacement.allocatedInstancesCount, request.resourceCount);

        RequestBrokerState day2RemovalRequest = new RequestBrokerState();
        day2RemovalRequest.documentSelfLink = extractId(request.documentSelfLink) + "-removal";
        day2RemovalRequest.resourceType = request.resourceType;
        day2RemovalRequest.resourceLinks = request.resourceLinks;
        day2RemovalRequest.operation = ContainerOperationType.DELETE.id;

        day2RemovalRequest = startRequest(day2RemovalRequest);

        String containerRemovalTaskLink = UriUtils.buildUriPath(
                ContainerRemovalTaskFactoryService.SELF_LINK,
                extractId(day2RemovalRequest.documentSelfLink));
        waitForTaskSuccess(containerRemovalTaskLink, ContainerRemovalTaskState.class);

        waitForRequestToComplete(day2RemovalRequest);

        // verify the resources have been removed:
        containerStateLinks = findResourceLinks(ContainerState.class, request.resourceLinks);
        assertTrue(containerStateLinks.isEmpty());

        // verified the placements have been released:
        groupResourcePlacement = getDocument(GroupResourcePlacementState.class,
                request.groupResourcePlacementLink);
        assertNotNull(groupResourcePlacement);
        assertEquals(groupResourcePlacement.allocatedInstancesCount, 0);

        // verify that the containers where removed from the docker mock
        Map<String, String> containerRefsByIds = MockDockerAdapterService
                .getContainerIdsWithContainerReferences();
        for (String containerRef : containerRefsByIds.values()) {
            for (String containerLink : containerStateLinks) {
                if (containerRef.endsWith(containerLink)) {
                    fail("Container State not removed with link: " + containerLink);
                }
            }
        }
    }

    @Test
    public void testRemoveCompositeComponentWhenLastContainerLeft() throws Throwable {
        CompositeComponent component = new CompositeComponent();
        component.name = "test-name";
        component = doPost(component, CompositeComponentFactoryService.SELF_LINK);

        ContainerState container1 = createContainer(component);
        ContainerState container2 = createContainer(component);
        ContainerState container3 = createContainer(component);

        // Delete only one of the containers part of the composition
        // - compositeComponent should not be removed
        List<String> containerStateLinks = new ArrayList<>(1);
        containerStateLinks.add(container1.documentSelfLink);

        RequestBrokerState day2RemovalRequest = new RequestBrokerState();
        day2RemovalRequest.resourceType = ResourceType.CONTAINER_TYPE.getName();
        day2RemovalRequest.resourceLinks = new HashSet<>(containerStateLinks);
        day2RemovalRequest.operation = ContainerOperationType.DELETE.id;

        day2RemovalRequest = startRequest(day2RemovalRequest);
        waitForRequestToComplete(day2RemovalRequest);

        // verify the container have been removed:
        containerStateLinks = findResourceLinks(ContainerState.class, containerStateLinks);
        assertTrue(containerStateLinks.isEmpty());

        // verify the CompositeComponent has not been removed
        CompositeComponent comp = getDocument(CompositeComponent.class, component.documentSelfLink);
        assertNotNull(comp);

        // Create and delete all containers part of the composition
        // - compositeComponent should be removed
        containerStateLinks = new ArrayList<>(2);
        containerStateLinks.add(container2.documentSelfLink);
        containerStateLinks.add(container3.documentSelfLink);

        String selfLink = extractId(day2RemovalRequest.documentSelfLink);
        day2RemovalRequest = new RequestBrokerState();
        day2RemovalRequest.documentSelfLink = selfLink + "-removal-2";
        day2RemovalRequest.resourceType = ResourceType.CONTAINER_TYPE.getName();
        day2RemovalRequest.resourceLinks = new HashSet<>(containerStateLinks);
        day2RemovalRequest.operation = ContainerOperationType.DELETE.id;

        day2RemovalRequest = startRequest(day2RemovalRequest);
        waitForRequestToComplete(day2RemovalRequest);

        // verify the container have been removed:
        containerStateLinks = findResourceLinks(ContainerState.class, containerStateLinks);
        assertTrue(containerStateLinks.isEmpty());

        // verify the CompositeComponent has been removed since all containers part of the
        // composition are removed.
        List<String> compositionComponentLinks = new ArrayList<>(1);
        compositionComponentLinks.add(component.documentSelfLink);
        waitFor(() -> {
            List<String> result = findResourceLinks(CompositeComponent.class,
                    compositionComponentLinks);
            return result.isEmpty();
        });
    }

    @Test
    public void testSystemContainerRemoveOperation() throws Throwable {
        ContainerState container = TestRequestStateFactory.createContainer();
        container.descriptionLink = containerDesc.documentSelfLink;
        container.adapterManagementReference = containerDesc.instanceAdapterReference;
        container.groupResourcePlacementLink = groupPlacementState.documentSelfLink;
        container.system = Boolean.TRUE;
        container = doPost(container, ContainerFactoryService.SELF_LINK);

        // try to delete system container
        List<String> containerStateLinks = new ArrayList<>(1);
        containerStateLinks.add(container.documentSelfLink);

        RequestBrokerState removalRequest = new RequestBrokerState();
        removalRequest.documentSelfLink = extractId(container.documentSelfLink) + "-removal";
        removalRequest.resourceType = request.resourceType;
        removalRequest.resourceLinks = new HashSet<>(containerStateLinks);
        removalRequest.operation = ContainerOperationType.DELETE.id;

        removalRequest = startRequest(removalRequest);
        waitForRequestToComplete(removalRequest);

        // verify the system container have not been removed:
        containerStateLinks = findResourceLinks(ContainerState.class, containerStateLinks);
        assertTrue("System container should not be removed.", !containerStateLinks.isEmpty());

    }

    @Test
    public void testRemovingOfCompositeDescriptionAndContainerRemovals() throws Throwable {
        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1");
        ContainerDescription desc2 = TestRequestStateFactory.createContainerDescription("name2");
        desc2.affinity = new String[] { desc1.name };
        desc2.portBindings = null;
        CompositeDescription compositeDesc = createCompositeDesc(desc1, desc2);

        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToComplete(request);

        assertEquals(1, request.resourceLinks.size());
        CompositeComponent cc = getDocument(CompositeComponent.class, request.resourceLinks.iterator().next());

        assertEquals(compositeDesc.descriptionLinks.size(), cc.componentLinks.size());

        List<String> containerLinks = cc.componentLinks;
        ContainerState container1 = getDocument(ContainerState.class, containerLinks.get(0));
        CompositeComponent compositeComp = getDocument(CompositeComponent.class,
                container1.compositeComponentLink);
        assertNotNull(compositeComp);

        // Delete CompositeDes
        delete(compositeDesc.documentSelfLink);

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<>(containerLinks);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);

        waitForRequestToComplete(request);

        container1 = searchForDocument(ContainerState.class, containerLinks.get(0));
        assertNull(container1);

        ContainerState container2 = searchForDocument(ContainerState.class, containerLinks.get(1));
        assertNull(container2);

        compositeComp = searchForDocument(CompositeComponent.class, compositeComp.documentSelfLink);
        assertNull(compositeComp);
    }

    @Test
    public void testRemovingOfNotClonedContainerDescriptionsAndContainerRemovals() throws Throwable {
        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1");
        ContainerDescription desc2 = TestRequestStateFactory.createContainerDescription("name2");
        desc2.affinity = new String[] { desc1.name };
        desc2.portBindings = null;
        CompositeDescription compositeDesc = createCompositeDesc(desc1, desc2);

        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToComplete(request);

        assertEquals(1, request.resourceLinks.size());
        CompositeComponent cc = getDocument(CompositeComponent.class, request.resourceLinks.iterator().next());

        assertEquals(compositeDesc.descriptionLinks.size(), cc.componentLinks.size());

        List<String> containerLinks = cc.componentLinks;
        ContainerState container1 = getDocument(ContainerState.class, containerLinks.get(0));
        ContainerState container2 = getDocument(ContainerState.class, containerLinks.get(1));
        assertNotNull(container1);
        assertNotNull(container2);

        CompositeComponent compositeComp = getDocument(CompositeComponent.class,
                container1.compositeComponentLink);
        assertNotNull(compositeComp);

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<>(containerLinks);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);

        waitForRequestToComplete(request);

        ContainerDescription createdDesc1 = searchForDocument(ContainerDescription.class,
                container1.descriptionLink);
        assertNotNull(createdDesc1);

        ContainerDescription createdDesc2 = searchForDocument(ContainerDescription.class,
                container2.descriptionLink);
        assertNotNull(createdDesc2);

        container1 = searchForDocument(ContainerState.class, containerLinks.get(0));
        assertNull(container1);

        container2 = searchForDocument(ContainerState.class, containerLinks.get(1));
        assertNull(container2);

        compositeComp = searchForDocument(CompositeComponent.class, compositeComp.documentSelfLink);
        assertNull(compositeComp);

        CompositeDescription createdCompDesc = searchForDocument(CompositeDescription.class,
                compositeDesc.documentSelfLink);
        assertNotNull(createdCompDesc);
    }

    @Test
    public void testRemovingOfClonedContainerDescriptionsAndContainerRemovals() throws Throwable {
        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1",
                true, true);
        ContainerDescription desc2 = TestRequestStateFactory.createContainerDescription("name2",
                true, false);
        desc2.affinity = new String[] { desc1.name };
        CompositeDescription compositeDesc = createCompositeDesc(true, desc1, desc2);

        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToComplete(request);

        assertEquals(1, request.resourceLinks.size());
        CompositeComponent cc = getDocument(CompositeComponent.class, request.resourceLinks.iterator().next());

        assertEquals(compositeDesc.descriptionLinks.size(), cc.componentLinks.size());

        List<String> containerLinks = cc.componentLinks;
        ContainerState container1 = getDocument(ContainerState.class, containerLinks.get(0));
        ContainerState container2 = getDocument(ContainerState.class, containerLinks.get(1));
        assertNotNull(container1);
        assertNotNull(container2);
        // Assert that the description is indeed a clone
        assertNotEquals(desc1.documentSelfLink, container1.descriptionLink);
        assertNotEquals(desc2.documentSelfLink, container1.descriptionLink);

        CompositeComponent compositeComp = getDocument(CompositeComponent.class,
                container1.compositeComponentLink);
        assertNotNull(compositeComp);

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<>(containerLinks);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);

        waitForRequestToComplete(request);

        ContainerDescription createdDesc1 = searchForDocument(ContainerDescription.class,
                container1.descriptionLink);
        assertNull(createdDesc1);

        ContainerDescription createdDesc2 = searchForDocument(ContainerDescription.class,
                container2.descriptionLink);
        assertNull(createdDesc2);

        container1 = searchForDocument(ContainerState.class, containerLinks.get(0));
        assertNull(container1);

        container2 = searchForDocument(ContainerState.class, containerLinks.get(1));
        assertNull(container2);

        CompositeDescription createdCompDesc = searchForDocument(CompositeDescription.class,
                compositeDesc.documentSelfLink);
        assertNull(createdCompDesc);
        compositeComp = searchForDocument(CompositeComponent.class, compositeComp.documentSelfLink);
        assertNull(compositeComp);
    }

    /**
     * When multiple containers share the same container description, removing one of them should not delete the description.
     * The description should be deleted only when all containers, deployed from it get deleted.
     */
    @Test
    public void testScaleDownContainerShouldNotDeleteDescription() throws Throwable {
        ContainerDescription desc = TestRequestStateFactory
                .createContainerDescription("name", true, true);
        desc._cluster = 2;
        CompositeDescription compositeDesc = createCompositeDesc(true, desc);

        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToComplete(request);

        assertEquals(1, request.resourceLinks.size());
        CompositeComponent cc = getDocument(CompositeComponent.class, request.resourceLinks.iterator().next());

        ContainerState container1 = getDocument(ContainerState.class, cc.componentLinks.get(0));
        ContainerState container2 = getDocument(ContainerState.class, cc.componentLinks.get(1));
        // Assert that the description is a clone
        assertNotNull(container1);
        assertNotEquals(desc.documentSelfLink, container1.descriptionLink);

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<String>();
        request.resourceLinks.add(container1.documentSelfLink);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);
        waitForRequestToComplete(request);

        ContainerDescription createdDesc = searchForDocument(ContainerDescription.class,
                container1.descriptionLink);
        assertNotNull(createdDesc);

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<String>();
        request.resourceLinks.add(container2.documentSelfLink);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);
        waitForRequestToComplete(request);

        createdDesc = searchForDocument(ContainerDescription.class, container1.descriptionLink);
        assertNull(createdDesc);
    }

    /**
     * When multiple containers share the same container description, removing one of them should not delete the description.
     * The description should be deleted only when all containers, deployed from it get deleted.
     */
    @Test
    public void testRemoveContainerSharingContainerDescription() throws Throwable {
        ContainerDescription desc = TestRequestStateFactory.createContainerDescription("name",
                false, false);
        desc = doPost(desc, ContainerDescriptionService.FACTORY_LINK);

        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceDescriptionLink = desc.documentSelfLink;
        request = startRequest(request);
        request = waitForRequestToComplete(request);
        String documentLink1 = request.resourceLinks.iterator().next();

        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceDescriptionLink = desc.documentSelfLink;
        request = startRequest(request);
        request = waitForRequestToComplete(request);
        String documentLink2 = request.resourceLinks.iterator().next();

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<String>();
        request.resourceLinks.add(documentLink1);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);

        waitForRequestToComplete(request);

        ContainerDescription createdDesc = searchForDocument(ContainerDescription.class,
                desc.documentSelfLink);
        assertNotNull(createdDesc);

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<String>();
        request.resourceLinks.add(documentLink2);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);

        waitForRequestToComplete(request);

        createdDesc = searchForDocument(ContainerDescription.class, desc.documentSelfLink);
        assertNotNull(createdDesc);
    }

    /**
     * When a container is scaled and there's a request to remove all of them, there could be a
     * race condition that makes all the container removal tasks to try to remove the same container
     * description, and that shouldn't fail with an exception. See VBV-666.
     */
    @Test
    public void testRemoveApplicationWithScaledContainer() throws Throwable {
        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1",
                true, true);
        ContainerDescription desc2 = TestRequestStateFactory.createContainerDescription("name2",
                true, false);
        desc2.affinity = new String[] { desc1.name };
        desc1._cluster = 9;

        CompositeDescription compositeDesc = createCompositeDesc(true, desc1, desc2);

        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToComplete(request);

        assertEquals(1, request.resourceLinks.size());
        CompositeComponent cc = getDocument(CompositeComponent.class, request.resourceLinks.iterator().next());

        assertEquals(desc1._cluster + 1, cc.componentLinks.size());

        List<String> containerLinks = cc.componentLinks;
        ContainerState container1 = getDocument(ContainerState.class, containerLinks.get(0));
        ContainerState container2 = getDocument(ContainerState.class, containerLinks.get(1));
        ContainerState container3 = getDocument(ContainerState.class, containerLinks.get(2));
        assertNotNull(container1);
        assertNotNull(container2);
        assertNotNull(container3);

        CompositeComponent compositeComp = getDocument(CompositeComponent.class,
                container1.compositeComponentLink);
        assertNotNull(compositeComp);

        // Remove Containers
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<>(containerLinks);
        request.operation = ContainerOperationType.DELETE.id;
        request = startRequest(request);

        waitForRequestToComplete(request);

        ContainerDescription createdDesc1 = searchForDocument(ContainerDescription.class,
                container1.descriptionLink);
        assertNull(createdDesc1);

        ContainerDescription createdDesc2 = searchForDocument(ContainerDescription.class,
                container2.descriptionLink);
        assertNull(createdDesc2);

        container1 = searchForDocument(ContainerState.class, containerLinks.get(0));
        assertNull(container1);

        container2 = searchForDocument(ContainerState.class, containerLinks.get(1));
        assertNull(container2);

        CompositeDescription createdCompDesc = searchForDocument(CompositeDescription.class,
                compositeDesc.documentSelfLink);
        assertNull(createdCompDesc);
        compositeComp = searchForDocument(CompositeComponent.class, compositeComp.documentSelfLink);
        assertNull(compositeComp);
    }

    /**
     * Validate ports are released when container is removed
     */
    @Test
    public void testReleasePortsWhenRemoveContainer() throws Throwable {
        ContainerDescription desc = TestRequestStateFactory.createContainerDescription("name",
                false, true);
        desc = doPost(desc, ContainerDescriptionService.FACTORY_LINK);

        // create container
        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceDescriptionLink = desc.documentSelfLink;
        request = startRequest(request);
        request = waitForRequestToComplete(request);
        String documentLink = request.resourceLinks.iterator().next();
        hostPortProfileState = getDocument(HostPortProfileService.HostPortProfileState.class,
                hostPortProfileState.documentSelfLink);
        // ports allocated
        assertTrue(hostPortProfileState.reservedPorts
                .entrySet()
                .stream()
                .allMatch(p -> documentLink.equals(p.getValue())));

        // remove Container
        request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.resourceLinks = new HashSet<String>();
        request.resourceLinks.add(documentLink);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);

        waitForRequestToComplete(request);
        hostPortProfileState = getDocument(HostPortProfileService.HostPortProfileState.class,
                hostPortProfileState.documentSelfLink);
        // ports allocated
        assertTrue(hostPortProfileState.reservedPorts
                .entrySet()
                .stream()
                .noneMatch(p -> documentLink.equals(p.getValue())));
    }

    private ContainerState createContainer(CompositeComponent component) throws Throwable {
        ContainerState container = TestRequestStateFactory.createContainer();
        container.descriptionLink = containerDesc.documentSelfLink;
        container.adapterManagementReference = containerDesc.instanceAdapterReference;
        container.compositeComponentLink = component.documentSelfLink;
        container.groupResourcePlacementLink = groupPlacementState.documentSelfLink;
        container = doPost(container, ContainerFactoryService.SELF_LINK);
        return container;
    }
}