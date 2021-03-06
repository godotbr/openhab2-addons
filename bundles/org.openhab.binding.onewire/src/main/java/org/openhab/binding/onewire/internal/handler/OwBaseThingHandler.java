/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.onewire.internal.handler;

import static org.openhab.binding.onewire.internal.OwBindingConstants.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.onewire.internal.OwDynamicStateDescriptionProvider;
import org.openhab.binding.onewire.internal.OwException;
import org.openhab.binding.onewire.internal.SensorId;
import org.openhab.binding.onewire.internal.device.AbstractOwDevice;
import org.openhab.binding.onewire.internal.device.OwChannelConfig;
import org.openhab.binding.onewire.internal.device.OwSensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link OwBaseThingHandler} class defines a handler for simple OneWire devices
 *
 * @author Jan N. Klug - Initial contribution
 */
@NonNullByDefault
public abstract class OwBaseThingHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(OwBaseThingHandler.class);

    protected static final int PROPERTY_UPDATE_INTERVAL = 5000; // in ms
    protected static final int PROPERTY_UPDATE_MAX_RETRY = 5;

    private static final Set<String> REQUIRED_PROPERTIES = Collections
            .unmodifiableSet(Stream.of(PROPERTY_MODELID, PROPERTY_VENDOR).collect(Collectors.toSet()));

    protected List<String> requiredProperties = new ArrayList<>(REQUIRED_PROPERTIES);
    protected Set<OwSensorType> supportedSensorTypes;

    protected final List<AbstractOwDevice> sensors = new ArrayList<AbstractOwDevice>();
    protected @NonNullByDefault({}) SensorId sensorId;
    protected @NonNullByDefault({}) OwSensorType sensorType;

    protected long lastRefresh = 0;
    protected long refreshInterval = 300 * 1000;

    protected boolean validConfig = false;
    protected boolean showPresence = false;

    protected OwDynamicStateDescriptionProvider dynamicStateDescriptionProvider;

    protected @Nullable ScheduledFuture<?> updateTask;

    public OwBaseThingHandler(Thing thing, OwDynamicStateDescriptionProvider dynamicStateDescriptionProvider,
            Set<OwSensorType> supportedSensorTypes) {
        super(thing);

        this.dynamicStateDescriptionProvider = dynamicStateDescriptionProvider;
        this.supportedSensorTypes = supportedSensorTypes;
    }

    public OwBaseThingHandler(Thing thing, OwDynamicStateDescriptionProvider dynamicStateDescriptionProvider,
            Set<OwSensorType> supportedSensorTypes, Set<String> requiredProperties) {
        super(thing);

        this.dynamicStateDescriptionProvider = dynamicStateDescriptionProvider;
        this.supportedSensorTypes = supportedSensorTypes;
        this.requiredProperties.addAll(requiredProperties);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            lastRefresh = 0;
            logger.trace("scheduled {} for refresh", this.thing.getUID());
        }
    }

    @Override
    public void initialize() {
        configureThingHandler();
    }

    protected boolean configureThingHandler() {
        Configuration configuration = getConfig();
        Map<String, String> properties = thing.getProperties();

        if (getBridge() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "bridge missing");
            return false;
        }
        sensors.clear();

        if (configuration.get(CONFIG_ID) != null) {
            String sensorId = (String) configuration.get(CONFIG_ID);
            try {
                this.sensorId = new SensorId(sensorId);
            } catch (IllegalArgumentException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "sensor id format mismatch");
                return false;
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "sensor id missing");
            return false;
        }

        if (configuration.get(CONFIG_REFRESH) != null) {
            refreshInterval = ((BigDecimal) configuration.get(CONFIG_REFRESH)).intValue() * 1000;
        } else {
            refreshInterval = 300 * 1000;
        }

        if (thing.getChannel(CHANNEL_PRESENT) != null) {
            showPresence = true;
        }

        // check if all required properties are present. update if not
        for (String property : requiredProperties) {
            if (!properties.containsKey(property)) {
                updateSensorProperties();
                return false;
            }
        }

        sensorType = OwSensorType.valueOf(properties.get(PROPERTY_MODELID));
        if (!supportedSensorTypes.contains(sensorType)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "sensor type not supported by this thing type");
            return false;
        }

        lastRefresh = 0;
        return true;
    }

    protected void configureThingChannels() {
        ThingBuilder thingBuilder = editThing();

        logger.debug("configuring sensors for {}", thing.getUID());

        // remove unwanted channels
        Set<String> existingChannelIds = thing.getChannels().stream().map(channel -> channel.getUID().getId())
                .collect(Collectors.toSet());
        Set<String> wantedChannelIds = SENSOR_TYPE_CHANNEL_MAP.get(sensorType).stream()
                .map(channelConfig -> channelConfig.channelId).collect(Collectors.toSet());
        existingChannelIds.stream().filter(channelId -> !wantedChannelIds.contains(channelId))
                .forEach(channelId -> removeChannelIfExisting(thingBuilder, channelId));

        // add or update wanted channels
        SENSOR_TYPE_CHANNEL_MAP.get(sensorType).stream().forEach(channelConfig -> {
            addChannelIfMissingAndEnable(thingBuilder, channelConfig);
        });

        updateThing(thingBuilder.build());

        try {
            sensors.get(0).configureChannels();
        } catch (OwException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        }

        validConfig = true;
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE);
    }

    /**
     * check if thing can be refreshed from the bridge handler
     *
     * @return true if thing can be refreshed
     */
    public boolean isRefreshable() {
        return super.isInitialized()
                && this.thing.getStatusInfo().getStatusDetail() != ThingStatusDetail.CONFIGURATION_ERROR
                && this.thing.getStatusInfo().getStatusDetail() != ThingStatusDetail.BRIDGE_OFFLINE;
    }

    /**
     * refresh this thing
     *
     * needs proper exception handling for refresh errors if overridden
     *
     * @param bridgeHandler bridge handler to use for communication with ow bus
     * @param now current time
     */
    public void refresh(OwserverBridgeHandler bridgeHandler, long now) {
        try {
            Boolean forcedRefresh = lastRefresh == 0;
            if (now >= (lastRefresh + refreshInterval)) {
                logger.trace("refreshing {}", this.thing.getUID());

                lastRefresh = now;

                if (!sensors.get(0).checkPresence(bridgeHandler)) {
                    logger.trace("sensor not present");
                    return;
                }

                for (int i = 0; i < sensors.size(); i++) {
                    logger.trace("refreshing sensor {} ({})", i, sensors.get(i).getSensorId());
                    sensors.get(i).refresh(bridgeHandler, forcedRefresh);
                }
            }
        } catch (OwException e) {
            logger.debug("{}: refresh exception {}", this.thing.getUID(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "refresh exception");
        }

    }

    /**
     * update presence status to present state of slave
     *
     * @param presentState current present state
     */
    public void updatePresenceStatus(State presentState) {
        if (OnOffType.ON.equals(presentState)) {
            updateStatus(ThingStatus.ONLINE);
            if (showPresence) {
                updateState(CHANNEL_PRESENT, OnOffType.ON);
            }
        } else if (OnOffType.OFF.equals(presentState)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "slave missing");
            if (showPresence) {
                updateState(CHANNEL_PRESENT, OnOffType.OFF);
            }
        } else {
            updateStatus(ThingStatus.UNKNOWN);
            if (showPresence) {
                updateState(CHANNEL_PRESENT, UnDefType.UNDEF);
            }
        }
    }

    /**
     * post update to channel
     *
     * @param channelId channel id
     * @param state new channel state
     */
    public void postUpdate(String channelId, State state) {
        if (this.thing.getChannel(channelId) != null) {
            updateState(channelId, state);
        } else {
            logger.warn("{} missing channel {} when posting update {}", this.thing.getUID(), channelId, state);
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE
                && getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.BRIDGE_OFFLINE) {
            if (validConfig) {
                updatePresenceStatus(UnDefType.UNDEF);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
            }
        } else if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void dispose() {
        dynamicStateDescriptionProvider.removeDescriptionsForThing(thing.getUID());
        super.dispose();
    }

    /**
     * add this sensor to the property update list of the bridge handler
     *
     */
    protected void updateSensorProperties() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "bridge not found");
            return;
        }

        OwserverBridgeHandler bridgeHandler = (OwserverBridgeHandler) bridge.getHandler();
        if (bridgeHandler == null) {
            logger.debug("bridgehandler for {} not available for scheduling property update, retrying in 5s",
                    thing.getUID());
            scheduler.schedule(() -> {
                updateSensorProperties();
            }, 5000, TimeUnit.MILLISECONDS);
            return;
        }

        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "required properties missing");
        bridgeHandler.scheduleForPropertiesUpdate(thing);
        
    }

    /**
     * thing specific update method for sensor properties
     *
     * called by the bridge handler
     *
     * @param bridgeHandler the bridge handler to be used
     * @return properties to be added to the properties map
     * @throws OwException
     */
    public void updateSensorProperties(OwserverBridgeHandler bridgeHandler) throws OwException {
        Map<String, String> properties = editProperties();
        OwSensorType sensorType = bridgeHandler.getType(sensorId);
        properties.put(PROPERTY_MODELID, sensorType.toString());
        properties.put(PROPERTY_VENDOR, "Dallas/Maxim");

        updateProperties(properties);

        logger.trace("updated modelid/vendor to {} / {}", sensorType.name(), "Dallas/Maxim");
    }

    /**
     * get the dynamic state description provider for this thing
     *
     * @return
     */
    public @Nullable OwDynamicStateDescriptionProvider getDynamicStateDescriptionProvider() {
        return dynamicStateDescriptionProvider;
    }

    /**
     * remove a channel during initialization if it exists
     *
     * @param thingBuilder ThingBuilder of the edited thing
     * @param channelId id of the channel
     */
    protected void removeChannelIfExisting(ThingBuilder thingBuilder, String channelId) {
        if (thing.getChannel(channelId) != null) {
            thingBuilder.withoutChannel(new ChannelUID(thing.getUID(), channelId));
        }
    }

    /**
     * adds (or replaces) a channel and enables it within the sensor (configuration preserved, default sensor)
     *
     * @param thingBuilder ThingBuilder of the edited thing
     * @param channelConfig a OwChannelConfig for the new channel
     * @return the newly created channel
     */
    protected Channel addChannelIfMissingAndEnable(ThingBuilder thingBuilder, OwChannelConfig channelConfig) {
        return addChannelIfMissingAndEnable(thingBuilder, channelConfig, null, 0);
    }

    /**
     * adds (or replaces) a channel and enables it within the sensor (configuration overridden, default sensor)
     *
     * @param thingBuilder ThingBuilder of the edited thing
     * @param channelConfig a OwChannelConfig for the new channel
     * @param configuration the new Configuration for this channel
     * @return the newly created channel
     */
    protected Channel addChannelIfMissingAndEnable(ThingBuilder thingBuilder, OwChannelConfig channelConfig,
            Configuration configuration) {
        return addChannelIfMissingAndEnable(thingBuilder, channelConfig, configuration, 0);
    }

    /**
     * adds (or replaces) a channel and enables it within the sensor (configuration preserved)
     *
     * @param thingBuilder ThingBuilder of the edited thing
     * @param channelConfig a OwChannelConfig for the new channel
     * @param sensorNo number of sensor that provides this channel
     * @return the newly created channel
     */
    protected Channel addChannelIfMissingAndEnable(ThingBuilder thingBuilder, OwChannelConfig channelConfig,
            int sensorNo) {
        return addChannelIfMissingAndEnable(thingBuilder, channelConfig, null, sensorNo);
    }

    /**
     * adds (or replaces) a channel and enables it within the sensor (configuration overridden)
     *
     * @param thingBuilder ThingBuilder of the edited thing
     * @param channelConfig a OwChannelConfig for the new channel
     * @param configuration the new Configuration for this channel
     * @param sensorNo number of sensor that provides this channel
     * @return the newly created channel
     */
    protected Channel addChannelIfMissingAndEnable(ThingBuilder thingBuilder, OwChannelConfig channelConfig,
            @Nullable Configuration configuration, int sensorNo) {
        Channel channel = thing.getChannel(channelConfig.channelId);
        Configuration config = configuration;
        String label = channelConfig.label;

        // remove channel if wrong type uid and preserve config if not overridden
        if (channel != null && !channelConfig.channelTypeUID.equals(channel.getChannelTypeUID())) {
            removeChannelIfExisting(thingBuilder, channelConfig.channelId);
            if (config == null) {
                config = channel.getConfiguration();
            }
            channel = null;
        }

        // create channel if missing
        if (channel == null) {
            ChannelBuilder channelBuilder = ChannelBuilder
                    .create(new ChannelUID(thing.getUID(), channelConfig.channelId),
                            ACCEPTED_ITEM_TYPES_MAP.get(channelConfig.channelId))
                    .withType(channelConfig.channelTypeUID);
            if (label != null) {
                channelBuilder.withLabel(label);
            }
            if (config != null) {
                channelBuilder.withConfiguration(config);
            }
            channel = channelBuilder.build();
            thingBuilder.withChannel(channel);
        }

        // enable channel in sensor
        sensors.get(sensorNo).enableChannel(channelConfig.channelId);

        return channel;
    }
}
