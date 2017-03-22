/*
 * ============================================================================
` * GNU General Public License
 * ============================================================================
 *
 * Copyright (C) 2015 Infinite Automation Software. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * When signing a commercial license with Infinite Automation Software,
 * the following extension to GPL is made. A special exception to the GPL is
 * included to allow you to distribute a combined work that includes BAcnet4J
 * without being obliged to provide the source code for any proprietary components.
 *
 * See www.infiniteautomation.com for commercial license options.
 *
 * @author Matthew Lohbihler
 */
package com.serotonin.bacnet4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.cache.CachePolicies;
import com.serotonin.bacnet4j.cache.RemoteEntityCache;
import com.serotonin.bacnet4j.enums.MaxApduLength;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.event.DeviceEventHandler;
import com.serotonin.bacnet4j.event.ExceptionDispatcher;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.NetworkIdentifier;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.obj.DeviceObject;
import com.serotonin.bacnet4j.obj.mixin.CovContext;
import com.serotonin.bacnet4j.service.VendorServiceKey;
import com.serotonin.bacnet4j.service.confirmed.ConfirmedEventNotificationRequest;
import com.serotonin.bacnet4j.service.confirmed.ConfirmedRequestService;
import com.serotonin.bacnet4j.service.confirmed.DeviceCommunicationControlRequest.EnableDisable;
import com.serotonin.bacnet4j.service.unconfirmed.IAmRequest;
import com.serotonin.bacnet4j.service.unconfirmed.UnconfirmedEventNotificationRequest;
import com.serotonin.bacnet4j.service.unconfirmed.UnconfirmedRequestService;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.SequenceDefinition;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.Destination;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.ServicesSupported;
import com.serotonin.bacnet4j.type.constructed.TimeStamp;
import com.serotonin.bacnet4j.type.enumerated.ErrorClass;
import com.serotonin.bacnet4j.type.enumerated.ErrorCode;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.EventType;
import com.serotonin.bacnet4j.type.enumerated.NotifyType;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.enumerated.Segmentation;
import com.serotonin.bacnet4j.type.error.ErrorClassAndCode;
import com.serotonin.bacnet4j.type.notificationParameters.NotificationParameters;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RemoteDeviceDiscoverer;
import com.serotonin.bacnet4j.util.RemoteDeviceFinder;
import com.serotonin.bacnet4j.util.RemoteDeviceFinder.RemoteDeviceFuture;

import lohbihler.warp.WarpScheduledExecutorService;
import lohbihler.warp.WarpUtils;

/**
 * Enhancements:
 * - Optional persistence of COV subscriptions
 * - default character string encoding
 * - BIBBs (B-OWS) (services to implement)
 * - AE-N-A
 * - AE-ACK-A
 * - AE-INFO-A
 * - AE-ESUM-A
 * - SCHED-A
 * - T-VMT-A
 * - T-ATR-A
 * - DM-DDB-A,B
 * - DM-DOB-A,B
 * - DM-DCC-A
 * - DM-TS-A
 * - DM-UTC-A
 * - DM-RD-A
 * - DM-BR-A
 * - NM-CE-A
 */
public class LocalDevice {
    static final Logger LOG = LoggerFactory.getLogger(LocalDevice.class);

    private final Transport transport;

    /**
     * The other objects contained by this device.
     */
    private final List<BACnetObject> localObjects = new CopyOnWriteArrayList<>();

    /**
     * The policies used for caching of devices, objects, and properties.
     */
    private final CachePolicies cachePolicies = new CachePolicies();

    /**
     * A collection of known peer devices on the network.
     */
    private final RemoteEntityCache<Integer, RemoteDevice> remoteDeviceCache;

    /**
     * The BACnet object that represents this as the local device.
     */
    private BACnetObject deviceObject;

    /**
     * The clock used for all timing, except for Object.wait and Thread.sleep calls.
     */
    private Clock clock = Clock.systemUTC();

    private boolean initialized;

    /**
     * The local password of the device. Used in the ReinitializeDeviceRequest service.
     */
    private String password = "";

    /**
     * The list of all COV subscriptions currently active in the device.
     */
    private final Map<ObjectIdentifier, List<CovContext>> covContexts = new ConcurrentHashMap<>();

    // Event listeners
    private final DeviceEventHandler eventHandler = new DeviceEventHandler();
    private final ExceptionDispatcher exceptionDispatcher = new ExceptionDispatcher();

    private ScheduledExecutorService timer;

    public static final Map<VendorServiceKey, SequenceDefinition> vendorServiceRequestResolutions = new HashMap<>();
    public static final Map<VendorServiceKey, SequenceDefinition> vendorServiceResultResolutions = new HashMap<>();

    /**
     * Useful when objects want to make COV subscriptions, in that it will provide a device-unique id.
     */
    private final AtomicInteger nextProcessId = new AtomicInteger(1);

    public LocalDevice(final int deviceNumber, final Transport transport) {
        this.transport = transport;
        transport.setLocalDevice(this);
        remoteDeviceCache = new RemoteEntityCache<>(this);

        afterInit(deviceNumber);
    }

    private void afterInit(final int deviceNumber) {
        try {
            // Initialize the device object.
            deviceObject = new DeviceObject(this, deviceNumber);

            // The device object is added to the list of object here rather than the letting the base class do it,
            // because it has to initialize a few things beforehand.
            addObjectInternal(deviceObject);
        } catch (final BACnetServiceException e) {
            // Should not happen
            throw new RuntimeException(e);
        }
    }

    public Clock getClock() {
        return clock;
    }

    public void setClock(final Clock clock) {
        if (initialized)
            throw new IllegalStateException("Clock needs to be set before LocalDevice is initialized");
        this.clock = clock;
    }

    public Network getNetwork() {
        return transport.getNetwork();
    }

    public NetworkIdentifier getNetworkIdentifier() {
        return transport.getNetworkIdentifier();
    }

    public CachePolicies getCachePolicies() {
        return cachePolicies;
    }

    public Map<ObjectIdentifier, List<CovContext>> getCovContexts() {
        return covContexts;
    }

    public ObjectIdentifier getId() {
        return deviceObject.getId();
    }

    public int getInstanceNumber() {
        return deviceObject.getInstanceId();
    }

    public <T extends Encodable> T getProperty(final PropertyIdentifier pid) {
        return deviceObject.getProperty(pid);
    }

    public LocalDevice writePropertyInternal(final PropertyIdentifier pid, final Encodable value) {
        deviceObject.writePropertyInternal(pid, value);
        return this;
    }

    public DeviceEventHandler getEventHandler() {
        return eventHandler;
    }

    public ExceptionDispatcher getExceptionDispatcher() {
        return exceptionDispatcher;
    }

    public int getNextProcessId() {
        return nextProcessId.getAndIncrement();
    }

    /**
     * @return the number of bytes sent by the transport
     */
    public long getBytesOut() {
        return transport.getBytesOut();
    }

    /**
     * @return the number of bytes received by the transport
     */
    public long getBytesIn() {
        return transport.getBytesIn();
    }

    public synchronized LocalDevice initialize() throws Exception {
        timer = new WarpScheduledExecutorService(clock);
        transport.initialize();
        initialized = true;

        // If the device id is uninitialized, try to find an available number to use.
        if (getInstanceNumber() == ObjectIdentifier.UNINITIALIZED) {
            final int attempts = 10;
            final int rangeSize = 20;

            final Random random = new Random();
            int remaining = attempts;
            while (attempts > 0) {
                final int from = random.nextInt(ObjectIdentifier.UNINITIALIZED - rangeSize);
                final List<Integer> idList = IntStream.range(from, from + rangeSize).boxed()
                        .collect(Collectors.toList());

                final DeviceEventAdapter listener = new DeviceEventAdapter() {
                    @Override
                    public void iAmReceived(final RemoteDevice d) {
                        LOG.info("Device id {} is not available", d.getInstanceNumber());
                        idList.remove(new Integer(d.getInstanceNumber()));
                    }
                };

                getEventHandler().addListener(listener);
                sendGlobalBroadcast(new WhoIsRequest(from, from + rangeSize - 1));

                LOG.info("Waiting for incoming IAms");
                WarpUtils.sleep(clock, 10, TimeUnit.SECONDS);
                getEventHandler().removeListener(listener);

                if (!idList.isEmpty()) {
                    LOG.info("Found {} ids that are still available. Choosing {}", idList.size(), idList.get(0));
                    deviceObject.writePropertyInternal(PropertyIdentifier.objectIdentifier,
                            new ObjectIdentifier(ObjectType.device, idList.get(0)));
                    break;
                }

                remaining--;
            }

            if (remaining == 0)
                throw new Exception("Could not find an available device id after " + attempts + " attempts");
        }

        return this;
    }

    public synchronized void terminate() {
        timer.shutdown();
        try {
            if (!timer.awaitTermination(10, TimeUnit.SECONDS))
                LOG.warn("BACnet4J timer did not shutdown within 10 seconds");
        } catch (final InterruptedException e) {
            LOG.warn("Interrupted while waiting for shutdown of executors", e);
        }
        transport.terminate();
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Executors
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Schedules the given command for later execution.
     */
    public ScheduledFuture<?> schedule(final Runnable command, final long period, final TimeUnit unit) {
        return timer.schedule(command, period, unit);
    }

    /**
     * Schedules the given command for later execution.
     */
    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period,
            final TimeUnit unit) {
        return timer.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    /**
     * Schedules the given command for later execution.
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay,
            final TimeUnit unit) {
        return timer.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    /**
     * Submits the given task for immediate execution.
     */
    public Future<?> submit(final Runnable task) {
        return timer.submit(task);
    }

    /**
     * Submits the given task for immediate execution.
     */
    public void execute(final Runnable task) {
        timer.execute(task);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Device configuration.
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public String getPassword() {
        return password;
    }

    public void setPassword(final String password) {
        if (password == null)
            this.password = "";
        else
            this.password = password;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Local object management
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public BACnetObject getObjectRequired(final ObjectIdentifier id) throws BACnetServiceException {
        final BACnetObject o = getObject(id);
        if (o == null)
            throw new BACnetServiceException(ErrorClass.object, ErrorCode.unknownObject);
        return o;
    }

    public List<BACnetObject> getLocalObjects() {
        return localObjects;
    }

    public BACnetObject getObject(final ObjectIdentifier id) {
        ObjectIdentifier oidToFind = id;
        // Treat calls for device 0x3FFFFF as calls for the local device object. See 15.5.2.
        if (id.getObjectType().equals(ObjectType.device) && id.getInstanceNumber() == ObjectIdentifier.UNINITIALIZED) {
            oidToFind = new ObjectIdentifier(ObjectType.device, getInstanceNumber());
        }

        for (final BACnetObject obj : localObjects) {
            if (obj.getId().equals(oidToFind))
                return obj;
        }

        return null;
    }

    public BACnetObject getObject(final String name) {
        // TODO validate that this is not necessary.
        //        // Check if we need to look into the local device.
        //        if (name.equals(getObjectName()))
        //            return this;

        for (final BACnetObject obj : localObjects) {
            if (name.equals(obj.getObjectName()))
                return obj;
        }
        return null;
    }

    public void addObject(final BACnetObject obj) throws BACnetServiceException {
        // Don't allow the addition of devices.
        if (obj.getId().getObjectType().equals(ObjectType.device))
            throw new BACnetServiceException(ErrorClass.object, ErrorCode.dynamicCreationNotSupported);
        addObjectInternal(obj);
    }

    private void addObjectInternal(final BACnetObject obj) throws BACnetServiceException {
        if (getObject(obj.getId()) != null)
            throw new BACnetServiceException(ErrorClass.object, ErrorCode.objectIdentifierAlreadyExists);
        if (getObject(obj.getObjectName()) != null)
            throw new BACnetServiceException(ErrorClass.object, ErrorCode.duplicateName);

        localObjects.add(obj);
    }

    public ObjectIdentifier getNextInstanceObjectIdentifier(final ObjectType objectType) {
        return new ObjectIdentifier(objectType, getNextInstanceObjectNumber(objectType));
    }

    public int getNextInstanceObjectNumber(final ObjectType objectType) {
        // Make a list of existing ids.
        final List<Integer> ids = new ArrayList<>();
        final int type = objectType.intValue();
        ObjectIdentifier id;
        for (final BACnetObject obj : localObjects) {
            id = obj.getId();
            if (id.getObjectType().intValue() == type)
                ids.add(id.getInstanceNumber());
        }

        // Sort the list.
        Collections.sort(ids);

        // Find the first hole in the list.
        int i = 0;
        for (; i < ids.size(); i++) {
            if (ids.get(i) != i)
                break;
        }

        return i;
    }

    public void removeObject(final ObjectIdentifier id) throws BACnetServiceException {
        final BACnetObject obj = getObject(id);
        if (obj != null) {
            localObjects.remove(obj);

            // Notify the object that it was removed.
            obj.terminate();
        } else
            throw new BACnetServiceException(ErrorClass.object, ErrorCode.unknownObject);
    }

    public ServicesSupported getServicesSupported() {
        return (ServicesSupported) deviceObject.getProperty(PropertyIdentifier.protocolServicesSupported);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Remote device management
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the cached remote device, or null if not found.
     *
     * @param instanceNumber
     * @return the remote device or null if not found.
     */
    public RemoteDevice getCachedRemoteDevice(final int instanceNumber) {
        return remoteDeviceCache.getCachedEntity(instanceNumber);
    }

    public RemoteDevice getCachedRemoteDevice(final Address address) {
        return remoteDeviceCache.getCachedEntity((rd) -> rd.getAddress().equals(address));
    }

    public RemoteDevice removeCachedRemoteDevice(final int instanceNumber) {
        return remoteDeviceCache.removeEntity(instanceNumber);
    }

    /**
     * Finds a remote device for the given instanceNumber by notifying a given callback. If a cached instance is found
     * the callback is called by the calling thread. Otherwise, a finder will be used to try to find it. If this is
     * successful the device will be cached.
     *
     * The benefits of this method are:
     * 1) It will cache the remote device if it is found
     * 2) No blocking is performed
     *
     * @param instanceNumber
     * @param callback
     * @param timeoutCallback
     * @param timeout
     * @param unit
     */
    public void getRemoteDevice(final int instanceNumber, final Consumer<RemoteDevice> callback,
            final Runnable timeoutCallback, final Runnable finallyCallback, final long timeout, final TimeUnit unit) {
        Objects.requireNonNull(callback);

        // Check for a cached instance.
        final RemoteDevice rd = getCachedRemoteDevice(instanceNumber);

        if (rd != null) {
            LOG.debug("Found a cached device: {}", instanceNumber);
            // Provide it to the callback in this thread.
            callback.accept(rd);
        } else {
            LOG.debug("Requesting the remote device from the remote device finder: {}", instanceNumber);
            RemoteDeviceFinder.findDevice(this, instanceNumber, (cbrd) -> {
                // Cache the device.
                remoteDeviceCache.putEntity(instanceNumber, cbrd, cachePolicies.getDevicePolicy(instanceNumber));

                // Notify the client callback
                callback.accept(cbrd);
            }, timeoutCallback, finallyCallback, timeout, unit);
        }
    }

    /**
     * Returns a future to get the remote device for the given instanceNumber. If a cached instance is found the future
     * will be set immediately. Otherwise, a finder will be used to try to find it. If this is successful the device
     * will be cached.
     *
     * The benefits of this method are:
     * 1) It will cache the remote device if it is found.
     * 2) It returns a cancelable future.
     *
     * If multiple threads are likely to request a remote device reference around the same time, it may be better to
     * use the blocking method below.
     *
     * @param instanceNumber
     * @return the remote device future
     */
    public RemoteDeviceFuture getRemoteDevice(final int instanceNumber) {
        return new RemoteDeviceFuture() {
            private RemoteDevice remoteDevice;
            private RemoteDeviceFuture future;

            {
                // Check for a cached instance
                final RemoteDevice rd = getCachedRemoteDevice(instanceNumber);

                if (rd != null) {
                    LOG.debug("Found a cached device: {}", instanceNumber);
                    remoteDevice = rd;
                } else {
                    LOG.debug("Creating a new future to get device: {}", instanceNumber);
                    future = RemoteDeviceFinder.findDevice(LocalDevice.this, instanceNumber);
                }
            }

            @Override
            public RemoteDevice get(final long timeoutMillis) throws BACnetException, CancellationException {
                if (remoteDevice != null)
                    return remoteDevice;

                final RemoteDevice rd = future.get(timeoutMillis);

                // Cache the device.
                remoteDeviceCache.putEntity(instanceNumber, rd, cachePolicies.getDevicePolicy(instanceNumber));

                return rd;
            }

            @Override
            public void cancel() {
                if (future != null)
                    future.cancel();
            }
        };
    }

    /**
     * Returns the remote device for the given instanceNumber using the default timeout. If a cached instance is not
     * found the finder will be used to try and find it. A timeout exception is thrown if it can't be found.
     *
     * @param instanceNumber
     * @return the remote device
     * @throws BACnetException
     *             if anything goes wrong, including timeout.
     */
    public RemoteDevice getRemoteDeviceBlocking(final int instanceNumber) throws BACnetException {
        return getRemoteDeviceBlocking(instanceNumber, transport.getTimeout());
    }

    /**
     * A list of existing futures for each device. Multiple threads may want the same device,
     * and so we also them all to wait on the same future. This has timeout implications since
     * the timeout will be based upon the first thread that made the request, meaning that
     * subsequent threads may experience a shorter timeout than requested.
     */
    private final Map<Integer, RemoteDeviceFuture> futures = new HashMap<>();

    /**
     * Returns the remote device for the given instanceNumber. If a cached instance is not found the finder will be used
     * to try and find it. A timeout exception is thrown if it can't be found.
     *
     * The benefits of this method are:
     * 1) It will cache the remote device if it is found.
     * 2) Multiple threads that request the same remote device around the same time will be joined on the same request
     *
     * If you require the ability to cancel a request, use the non-blocking method above.
     *
     * @param instanceNumber
     * @return the remote device
     * @throws BACnetException
     *             if anything goes wrong, including timeout.
     */
    public RemoteDevice getRemoteDeviceBlocking(final int instanceNumber, final long timeoutMillis)
            throws BACnetException {
        // Check for a cached instance
        RemoteDevice rd = getCachedRemoteDevice(instanceNumber);

        if (rd == null) {
            RemoteDeviceFuture future;
            synchronized (futures) {
                // Check if there is an existing future for the device.
                future = futures.get(instanceNumber);
                if (future == null) {
                    LOG.debug("Creating a new future to get device: {}", instanceNumber);
                    // Create a request to get a fresh copy.
                    future = RemoteDeviceFinder.findDevice(this, instanceNumber);
                    futures.put(instanceNumber, future);
                } else {
                    LOG.debug("Using existing future: {}", instanceNumber);
                }
            }

            // Wait for the device.
            LOG.debug("Waiting on future: {}", instanceNumber);
            if (timeoutMillis == 0)
                rd = future.get();
            else
                rd = future.get(timeoutMillis);

            LOG.debug("Future completed: {}", instanceNumber);

            // Multiple threads can wait on a single future, and only one thread need run the following code.
            synchronized (future) {
                if (futures.containsKey(instanceNumber)) {
                    LOG.debug("Doing futures cleanup: {}", instanceNumber);

                    // Remove the future.
                    futures.remove(instanceNumber);

                    // Cache the device.
                    remoteDeviceCache.putEntity(instanceNumber, rd, cachePolicies.getDevicePolicy(instanceNumber));
                }
            }
        }

        return rd;
    }

    public RemoteDeviceDiscoverer startRemoteDeviceDiscovery() {
        return startRemoteDeviceDiscovery(null);
    }

    /**
     * Creates and starts a remote device discovery. Discovered devices are added to the cache as they are found. The
     * returned discoverer must be stopped by the caller.
     *
     * @param callback
     *            optional client callback
     * @return the discoverer, which must be stopped by the caller
     */
    public RemoteDeviceDiscoverer startRemoteDeviceDiscovery(final Consumer<RemoteDevice> callback) {
        final Consumer<RemoteDevice> cachingCallback = (d) -> {
            // Cache the device.
            remoteDeviceCache.putEntity(d.getInstanceNumber(), d, cachePolicies.getDevicePolicy(d.getInstanceNumber()));

            // Call the given callback
            if (callback != null)
                callback.accept(d);
        };

        final RemoteDeviceDiscoverer discoverer = new RemoteDeviceDiscoverer(this, cachingCallback);
        discoverer.start();

        return discoverer;
    }

    /**
     * Updates the remote device with the given number with the given address, but only if the
     * remote device is cached. Otherwise, nothing happens.
     *
     * @param instanceNumber
     * @param address
     * @return
     */
    public void updateRemoteDevice(final int instanceNumber, final Address address) {
        if (address == null)
            throw new NullPointerException("addr cannot be null");
        final RemoteDevice d = getCachedRemoteDevice(instanceNumber);
        if (d != null) {
            d.setAddress(address);
        }
    }

    /**
     * Clears the cache of remote devices.
     */
    public void clearRemoteDevices() {
        remoteDeviceCache.clear();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Cached property management
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    //
    // Get properties

    public <T extends Encodable> T getCachedRemoteProperty(final int did, final ObjectIdentifier oid,
            final PropertyIdentifier pid) {
        return getCachedRemoteProperty(did, oid, pid, null);
    }

    public <T extends Encodable> T getCachedRemoteProperty(final int did, final ObjectIdentifier oid,
            final PropertyIdentifier pid, final UnsignedInteger pin) {
        final RemoteDevice rd = getCachedRemoteDevice(did);
        if (rd == null)
            return null;
        return rd.getObjectProperty(oid, pid, pin);
    }

    //
    // Set properties

    public void setCachedRemoteProperty(final int did, final ObjectIdentifier oid, final PropertyIdentifier pid,
            final Encodable value) {
        setCachedRemoteProperty(did, oid, pid, null, value);
    }

    public void setCachedRemoteProperty(final int did, final ObjectIdentifier oid, final PropertyIdentifier pid,
            final UnsignedInteger pin, final Encodable value) {
        if (value instanceof ErrorClassAndCode) {
            final ErrorClassAndCode e = (ErrorClassAndCode) value;
            if (ErrorClass.device.equals(e.getErrorClass())) {
                // Don't cache devices if the error is about the device. In fact, delete the cached device.
                remoteDeviceCache.removeEntity(did);
                return;
            }
        }

        final RemoteDevice rd = getCachedRemoteDevice(did);
        if (rd != null) {
            rd.setObjectProperty(oid, pid, pin, value);
        }
    }

    //
    // Remove properties

    public <T extends Encodable> T removeCachedRemoteProperty(final int did, final ObjectIdentifier oid,
            final PropertyIdentifier pid) {
        return removeCachedRemoteProperty(did, oid, pid, null);
    }

    public <T extends Encodable> T removeCachedRemoteProperty(final int did, final ObjectIdentifier oid,
            final PropertyIdentifier pid, final UnsignedInteger pin) {
        final RemoteDevice rd = getCachedRemoteDevice(did);
        if (rd == null)
            return null;
        return rd.removeObjectProperty(oid, pid, pin);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Message sending
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    public ServiceFuture send(final RemoteDevice d, final ConfirmedRequestService serviceRequest) {
        //        validateSupportedService(d, serviceRequest);
        return transport.send(d.getAddress(), d.getMaxAPDULengthAccepted(), d.getSegmentationSupported(),
                serviceRequest);
    }

    public ServiceFuture send(final Address address, final ConfirmedRequestService serviceRequest) {
        final RemoteDevice d = getCachedRemoteDevice(address);
        if (d == null) {
            // Just use some hopeful defaults.
            return transport.send(address, MaxApduLength.UP_TO_50.getMaxLengthInt(), Segmentation.noSegmentation,
                    serviceRequest);
        }
        return send(d, serviceRequest);
    }

    public void send(final RemoteDevice d, final ConfirmedRequestService serviceRequest,
            final ResponseConsumer consumer) {
        //        validateSupportedService(d, serviceRequest);
        transport.send(d.getAddress(), d.getMaxAPDULengthAccepted(), d.getSegmentationSupported(), serviceRequest,
                consumer);
    }

    public void send(final Address address, final ConfirmedRequestService serviceRequest,
            final ResponseConsumer consumer) {
        final RemoteDevice d = getCachedRemoteDevice(address);
        if (d == null) {
            // Just use some hopeful defaults.
            transport.send(address, MaxApduLength.UP_TO_50.getMaxLengthInt(), Segmentation.noSegmentation,
                    serviceRequest, consumer);
        } else
            send(d, serviceRequest, consumer);
    }

    public void send(final RemoteDevice d, final UnconfirmedRequestService serviceRequest) {
        transport.send(d.getAddress(), serviceRequest, false);
    }

    public void send(final Address address, final UnconfirmedRequestService serviceRequest) {
        transport.send(address, serviceRequest, false);
    }

    public void sendLocalBroadcast(final UnconfirmedRequestService serviceRequest) {
        final Address bcast = transport.getLocalBroadcastAddress();
        transport.send(bcast, serviceRequest, true);
    }

    public void sendGlobalBroadcast(final UnconfirmedRequestService serviceRequest) {
        transport.send(Address.GLOBAL, serviceRequest, true);
    }

    public void sendBroadcast(final Address address, final UnconfirmedRequestService serviceRequest) {
        transport.send(address, serviceRequest, true);
    }

    // Doesn't work because the service choice id is not the same as the index in services supported.
    // Besides, this should be done in the transport, and errors indicated to the callback.
    //    private void validateSupportedService(RemoteDevice d, Service service) {
    //        if (d.getServicesSupported() != null) {
    //            if (!d.getServicesSupported().getValue()[service.getChoiceId()])
    //                throw new BACnetRuntimeException("Remote device does not support service " + service.getClass());
    //        }
    //    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Intrinsic events
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("unchecked")
    public List<BACnetException> sendIntrinsicEvent(final ObjectIdentifier eventObjectIdentifier,
            final TimeStamp timeStamp, final int notificationClassId, final EventType eventType,
            final CharacterString messageText, final NotifyType notifyType, final EventState fromState,
            final EventState toState, final NotificationParameters eventValues) throws BACnetException {

        // Try to find a notification class with the given id in the local objects.
        BACnetObject nc = null;
        for (final BACnetObject obj : localObjects) {
            if (ObjectType.notificationClass.equals(obj.getId().getObjectType())) {
                final UnsignedInteger ncId = (UnsignedInteger) obj.getProperty(PropertyIdentifier.notificationClass);
                if (ncId != null && ncId.intValue() == notificationClassId) {
                    nc = obj;
                    break;
                }
            }
        }

        if (nc == null)
            throw new BACnetException("Notification class object not found for given id: " + notificationClassId);

        // Get the required properties from the notification class object.
        SequenceOf<Destination> recipientList = null;
        Boolean ackRequired = null;
        UnsignedInteger priority = null;
        try {
            recipientList = (SequenceOf<Destination>) nc.getPropertyRequired(PropertyIdentifier.recipientList);
            ackRequired = new Boolean(
                    ((EventTransitionBits) nc.getPropertyRequired(PropertyIdentifier.ackRequired)).contains(toState));

            // Determine which priority value to use based upon the toState.
            final SequenceOf<UnsignedInteger> priorities = (SequenceOf<UnsignedInteger>) nc
                    .getPropertyRequired(PropertyIdentifier.priority);
            if (toState.equals(EventState.normal))
                priority = priorities.get(3);
            else if (toState.equals(EventState.fault))
                priority = priorities.get(2);
            else
                // everything else is offnormal
                priority = priorities.get(1);
        } catch (final BACnetServiceException e) {
            // Should never happen, so wrap in a RTE
            throw new RuntimeException(e);
        }

        // Send the message to the destinations that are interested in it, while recording any exceptions in the result
        // list
        final List<BACnetException> sendExceptions = new ArrayList<>();
        for (final Destination destination : recipientList) {
            if (destination.isSuitableForEvent(timeStamp, toState)) {
                if (destination.getIssueConfirmedNotifications().booleanValue()) {
                    final ConfirmedEventNotificationRequest req = new ConfirmedEventNotificationRequest(
                            destination.getProcessIdentifier(), getId(), eventObjectIdentifier, timeStamp,
                            new UnsignedInteger(notificationClassId), priority, eventType, messageText, notifyType,
                            ackRequired, fromState, toState, eventValues);

                    if (destination.getRecipient().isAddress()) {
                        send(destination.getRecipient().getAddress(), req);
                    } else {
                        final RemoteDevice remoteDevice = getRemoteDevice(
                                destination.getRecipient().getDevice().getInstanceNumber()).get();
                        send(remoteDevice, req);
                    }
                } else {
                    Address address = null;
                    if (destination.getRecipient().isAddress())
                        address = destination.getRecipient().getAddress();
                    else {
                        final RemoteDevice remoteDevice = getRemoteDevice(
                                destination.getRecipient().getDevice().getInstanceNumber()).get();
                        if (remoteDevice != null)
                            address = remoteDevice.getAddress();
                    }

                    if (address != null) {
                        final UnconfirmedEventNotificationRequest req = new UnconfirmedEventNotificationRequest(
                                destination.getProcessIdentifier(), getId(), eventObjectIdentifier, timeStamp,
                                new UnsignedInteger(notificationClassId), priority, eventType, messageText, notifyType,
                                ackRequired, fromState, toState, eventValues);
                        transport.send(address, req, false);
                    }
                }
            }
        }

        return sendExceptions;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Communication control
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private final Object communicationControlMonitor = new Object();
    private EnableDisable communicationControlState = EnableDisable.enable;
    private ScheduledFuture<?> communicationControlFuture;

    public void setCommunicationControl(final EnableDisable enableDisable, final int minutes) {
        synchronized (communicationControlMonitor) {
            communicationControlState = enableDisable;
            cancelCommunicationControlFuture();
            if (enableDisable.isOneOf(EnableDisable.disableInitiation, EnableDisable.disable)) {
                if (minutes > 0) {
                    communicationControlFuture = schedule(() -> {
                        synchronized (communicationControlMonitor) {
                            communicationControlState = EnableDisable.enable;
                            communicationControlFuture = null;
                        }
                    }, minutes, TimeUnit.MINUTES);
                }
            }
        }
    }

    private void cancelCommunicationControlFuture() {
        if (communicationControlFuture != null) {
            communicationControlFuture.cancel(false);
            communicationControlFuture = null;
        }
    }

    public EnableDisable getCommunicationControlState() {
        return communicationControlState;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Convenience methods
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public Address[] getAllLocalAddresses() {
        return transport.getNetwork().getAllLocalAddresses();
    }

    public Address getLoopbackAddress() {
        return transport.getNetwork().getLoopbackAddress();
    }

    public IAmRequest getIAm() {
        return new IAmRequest(getId(),
                (UnsignedInteger) deviceObject.getProperty(PropertyIdentifier.maxApduLengthAccepted),
                (Segmentation) deviceObject.getProperty(PropertyIdentifier.segmentationSupported),
                (UnsignedInteger) deviceObject.getProperty(PropertyIdentifier.vendorIdentifier));
    }

    @Override
    public String toString() {
        return "" + deviceObject.getInstanceId() + ": " + deviceObject.getObjectName();
    }
}
