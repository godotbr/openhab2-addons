/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.netatmo.internal.channelhelper;

import io.rudolph.netatmo.api.common.model.Device;
import io.rudolph.netatmo.api.common.model.Module;
import io.rudolph.netatmo.api.energy.model.module.EnergyModule;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.openhab.binding.netatmo.NetatmoBindingConstants.CHANNEL_RF_STATUS;
import static org.openhab.binding.netatmo.NetatmoBindingConstants.CHANNEL_WIFI_STATUS;

/**
 * The {@link RadioHelper} handle specific behavior
 * of WIFI or RF devices and modules
 *
 * @author Gaël L'hopital - Initial contribution
 */
public class RadioHelper {
    private Logger logger = LoggerFactory.getLogger(RadioHelper.class);
    private Object module;


    public void setModule(Object module) {
        this.module = module;
    }

    public Optional<State> getNAThingProperty(String channelId) {

        if (module != null) {
            switch (channelId) {
                case CHANNEL_RF_STATUS:
                    if (!(module instanceof Module)) {
                        break;
                    }
                    Integer rfStatus = ((Module) module).getRfStrength();
                    if (rfStatus == null) {
                        break;
                    }
                    return Optional.of(new DecimalType(rfStatus));
                case CHANNEL_WIFI_STATUS:
                    Integer wifiStatus = null;
                    if (module instanceof EnergyModule) {
                        wifiStatus = ((EnergyModule) module).getWifiStrength();
                    }
                    if (module instanceof Device) {
                        wifiStatus = ((Device) module).getWifiStatus();
                    }
                    if (wifiStatus == null) {
                        break;
                    }
                    return Optional.of(new DecimalType(wifiStatus));
            }
            logger.warn("The module has no method to access {} property", channelId);
            return Optional.of(UnDefType.NULL);
        }
        return Optional.empty();
    }

}
