/**
 * Copyright (c) 2011, 2014 Eurotech and/or its affiliates
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Eurotech
 */
package org.eclipse.kura.net.admin.visitor.linux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.core.net.NetworkConfiguration;
import org.eclipse.kura.core.net.NetworkConfigurationVisitor;
import org.eclipse.kura.linux.net.iptables.LinuxFirewall;
import org.eclipse.kura.linux.net.iptables.NATRule;
import org.eclipse.kura.net.NetConfig;
import org.eclipse.kura.net.NetConfigIP4;
import org.eclipse.kura.net.NetInterfaceAddressConfig;
import org.eclipse.kura.net.NetInterfaceConfig;
import org.eclipse.kura.net.NetInterfaceStatus;
import org.eclipse.kura.net.NetInterfaceType;
import org.eclipse.kura.net.admin.visitor.linux.util.KuranetConfig;
import org.eclipse.kura.net.firewall.FirewallNatConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirewallNatConfigWriter implements NetworkConfigurationVisitor {
	
	private static final Logger s_logger = LoggerFactory.getLogger(FirewallNatConfigWriter.class);

	private static FirewallNatConfigWriter s_instance;
	
	public static FirewallNatConfigWriter getInstance() {
		
		if (s_instance == null) {
			s_instance = new FirewallNatConfigWriter();
		}
		return s_instance;
	}
	
	@Override
	public void visit(NetworkConfiguration config) throws KuraException {
			
		List<NetInterfaceConfig<? extends NetInterfaceAddressConfig>> netInterfaceConfigs = config.getModifiedNetInterfaceConfigs();
		 
		for (NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : netInterfaceConfigs) {
			if (netInterfaceConfig.getType() == NetInterfaceType.ETHERNET || netInterfaceConfig.getType() == NetInterfaceType.WIFI) {
				writeConfig(netInterfaceConfig, KuranetConfig.getProperties());
			}
		}
		
		applyNatConfig(config);
	}
	
	private void writeConfig(
			NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig,
			Properties kuraProps) throws KuraException {
		
		String interfaceName = netInterfaceConfig.getName();
		s_logger.debug("Writing NAT config for " + interfaceName);

		List<? extends NetInterfaceAddressConfig> netInterfaceAddressConfigs = null;
		netInterfaceAddressConfigs = netInterfaceConfig.getNetInterfaceAddresses();
		
		boolean natEnabled = false;
		boolean useMasquerade = false;
		String srcIface = null;
		String dstIface = null;
		if(netInterfaceAddressConfigs != null && netInterfaceAddressConfigs.size() > 0) {
    		for(NetInterfaceAddressConfig netInterfaceAddressConfig : netInterfaceAddressConfigs) {
    			List<NetConfig> netConfigs = netInterfaceAddressConfig.getConfigs();
    			if(netConfigs != null && netConfigs.size() > 0) {
    				for(int i=0; i<netConfigs.size(); i++) {
    					NetConfig netConfig = netConfigs.get(i);
    					if(netConfig instanceof FirewallNatConfig) {
    						natEnabled = true;
    						srcIface = ((FirewallNatConfig) netConfig).getSourceInterface();
    						dstIface = ((FirewallNatConfig) netConfig).getDestinationInterface();
    						useMasquerade = ((FirewallNatConfig) netConfig).isMasquerade();
    					}
    				}
    			}
    		}
    	}
		
		//set it all
    	if(kuraProps == null) {
    		s_logger.debug("kuraExtendedProps was null");
    		kuraProps = new Properties();
    	}
    	
    	StringBuilder sb = new StringBuilder().append("net.interface.").append(netInterfaceConfig.getName()).append(".config.nat.enabled");
    	kuraProps.put(sb.toString(), Boolean.toString(natEnabled));
    	if (natEnabled && (srcIface != null) && (dstIface != null)) {
    		sb = new StringBuilder().append("net.interface.").append(netInterfaceConfig.getName()).append(".config.nat.dst.interface");
        	kuraProps.put(sb.toString(), dstIface);
        	
        	sb = new StringBuilder().append("net.interface.").append(netInterfaceConfig.getName()).append(".config.nat.masquerade");
        	kuraProps.put(sb.toString(), Boolean.toString(useMasquerade));
    	}
    	
    	//write it
    	if(kuraProps != null && !kuraProps.isEmpty()) {
			try {
			    KuranetConfig.storeProperties(kuraProps);
			} catch (Exception e) {
				throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
			}
		}
	}
	
	private void applyNatConfig(NetworkConfiguration networkConfig) throws KuraException {
        LinuxFirewall firewall = LinuxFirewall.getInstance();
        firewall.replaceAllNatRules(getNatConfigs(networkConfig)); 
        firewall.enable();
	}
	
    private LinkedHashSet<NATRule> getNatConfigs(NetworkConfiguration networkConfig) {
        LinkedHashSet<NATRule> natConfigs = new LinkedHashSet<NATRule>();

        if(networkConfig != null) {
            ArrayList<String> wanList = new ArrayList<String>();
            ArrayList<String> natList = new ArrayList<String>();
           
            // get relevant interfaces
            for(NetInterfaceConfig<? extends NetInterfaceAddressConfig> netInterfaceConfig : networkConfig.getNetInterfaceConfigs()) {
                String interfaceName = netInterfaceConfig.getName();
                NetInterfaceStatus status = NetInterfaceStatus.netIPv4StatusUnknown;
                boolean isNat = false;
                
                for(NetInterfaceAddressConfig addressConfig : netInterfaceConfig.getNetInterfaceAddresses()) {
                    for(NetConfig netConfig : addressConfig.getConfigs()) {
                        if(netConfig instanceof NetConfigIP4) {
                            status = ((NetConfigIP4)netConfig).getStatus();
                        } else if(netConfig instanceof FirewallNatConfig) {
                            isNat = true;
                        }
                    }
                }
                
                if(NetInterfaceStatus.netIPv4StatusEnabledWAN.equals(status)) {
                    wanList.add(interfaceName);
                } else if(NetInterfaceStatus.netIPv4StatusEnabledLAN.equals(status) && isNat) {
                    natList.add(interfaceName);
                }
            }
            
            // create a nat rule for each interface to all potential wan interfaces       
            for(String sourceInterface : natList) {
                for(String destinationInterface : wanList) {
                    s_logger.debug("Got NAT rule for source: " + sourceInterface +", destination: " + destinationInterface);
                    natConfigs.add(new NATRule(sourceInterface, destinationInterface, true));
                }
            }
        }
        
        return natConfigs;
    }
}
