/**
 * Open Visual Trace Route
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.

 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.leo.traceroute.core.sniffer.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.jnetpcap.Pcap;
import org.jnetpcap.PcapAddr;
import org.jnetpcap.PcapBpfProgram;
import org.jnetpcap.PcapIf;
import org.jnetpcap.packet.PcapPacket;
import org.jnetpcap.packet.PcapPacketHandler;
import org.jnetpcap.protocol.network.Ip4;
import org.leo.traceroute.core.IComponent;
import org.leo.traceroute.core.ServiceFactory;
import org.leo.traceroute.core.network.INetworkInterfaceListener;
import org.leo.traceroute.core.network.INetworkService;
import org.leo.traceroute.core.sniffer.AbstractPacketPoint.Protocol;
import org.leo.traceroute.core.sniffer.IPacketListener;
import org.leo.traceroute.install.Env;
import org.leo.traceroute.install.Env.OS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Packet Sniffer $Id: JNetCapPacketSniffer.java 287 2016-10-30 20:35:16Z leolewis $
 * <pre>
 * </pre>
 * @author Leo Lewis
 */
public class JNetCapPacketSniffer extends AbstractSniffer
		implements PcapPacketHandler<Void>, IComponent, INetworkInterfaceListener<PcapIf> {

	private static final Logger LOGGER = LoggerFactory.getLogger(JNetCapPacketSniffer.class);

	/** Current device */
	private PcapIf _device;

	/** Captor */
	private volatile Pcap _captor;

	private ScheduledExecutorService _schedule;
	private ScheduledFuture<?> _scheduleStop;

	/**
	 * Constructor
	 */
	public JNetCapPacketSniffer() {

	}

	/**
	 * @see org.leo.traceroute.core.IComponent#init(org.leo.traceroute.core.ServiceFactory)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void init(final ServiceFactory services) throws Exception {
		super.init(services);
		_schedule = Executors.newScheduledThreadPool(2);
		if (_services != null) {
			((INetworkService<PcapIf>) _services.getJnetcapNetwork()).addListener(this);
		}
	}

	/**
	 * @see org.jnetpcap.packet.PcapPacketHandler#nextPacket(org.jnetpcap.packet.PcapPacket, java.lang.Object)
	 */
	@Override
	public void nextPacket(final PcapPacket packet, final Void v) {
		if (!_capturing) {
			return;
		}
		try {
			final Ip4 ip = packet.getHeader(new Ip4());
			if (ip != null) {
				final InetAddress dest = InetAddress.getByAddress(ip.destination());
				JNetCapPacketPoint point;
				// packets with dest to local device
				point = _services.getGeo().populateGeoDataForIP(new JNetCapPacketPoint(), dest.getHostAddress(),
						dest.getHostName());
				if (point == null || _localAddresses.contains(dest)) {
					point = _services.getGeo().populateGeoDataForLocalIp(new JNetCapPacketPoint(), dest.getHostAddress());
				}
				if (point != null) {
					if (point.setPacket(packet)) {
						final int c = _count.incrementAndGet();
						point.setNumber(c);
						point.setHostname(_services.getDnsLookup().dnsLookup(point.getIp()));
						point.setTs(System.currentTimeMillis());
						if (!_capturing) {
							return;
						}
						_capture.add(point);
						for (final IPacketListener listener : getListeners()) {
							listener.packetAdded(point);
						}
					}
				}
			}
		} catch (final IOException e) {
			for (final IPacketListener listener : getListeners()) {
				listener.error(e, this);
			}
		} catch (final Exception e) {
			LOGGER.error("Error while processing a packet", e);
		}
	}

	/**
	 * Start the capture
	 * @param types
	 * @param port
	 */
	@Override
	public void startCapture(final Set<Protocol> protocols, final String port, final boolean filterLenghtPackets,
			final int length, final String host, final int captureTimeSeconds) {
		_focusedPoint = null;
		_count.set(0);
		_capture.clear();
		_captureProtocols = protocols;
		_filterLenghtPackets = filterLenghtPackets;
		_length = length;
		if (captureTimeSeconds > 0) {
			_scheduleStop = _schedule.schedule(new Runnable() {
				@Override
				public void run() {
					endCapture();
				}
			}, captureTimeSeconds, TimeUnit.SECONDS);
		}
		_threadPool.execute(new Runnable() {
			@Override
			public void run() {
				for (final IPacketListener listener : getListeners()) {
					listener.startCapture();
				}
				_capturing = true;
				String filter = "";
				boolean first = true;
				for (final Protocol prot : _captureProtocols) {
					if (!first) {
						filter += ") or (";
					} else {
						filter = "(";
						first = false;
					}
					if (prot == Protocol.ICMP) {
						filter += prot.name().toLowerCase();
					} else {
						filter += convertPortToFilter(prot.name().toLowerCase(), port);
					}
					if (StringUtils.isNotEmpty(host)) {
						if (!first) {
							filter += " and ";
						} else {
							first = false;
						}
						filter += " dst host " + host;
					}
					if (filterLenghtPackets) {
						filter += " and greater " + length;
					}
				}
				if (!first) {
					filter += ") ";
				}
				LOGGER.info("Capture filter : " + filter);
				_filter = filter;
				doStartCapture();
			}
		});
	}

	private void doStartCapture() {
		try {
			closeCaptor();
			final StringBuilder err = new StringBuilder();
			_captor = Pcap.openLive(_device.getName(), 65535, Pcap.MODE_NON_PROMISCUOUS, 10 * 1000, err);
			if (_captor == null) {
				throw new IOException(err.toString());
			}
			final PcapBpfProgram filter = new PcapBpfProgram();
			final int r = _captor.compile(filter, _filter, 1, 0x0);
			_captor.setFilter(filter);
			_captor.loop(-1, this, null);
		} catch (final IOException e) {
			// notify error
			for (final IPacketListener listener : getListeners()) {
				listener.error(e, this);
			}
		} catch (final Exception e) {
			LOGGER.error("Error during sniffer", e);
		}
	}

	/**
	 * Stop capture
	 */
	@Override
	public void endCapture() {
		if (_scheduleStop != null) {
			_scheduleStop.cancel(true);
			_scheduleStop = null;
		}
		if (_capturing) {
			_capturing = false;
			closeCaptor();
			for (final IPacketListener listener : getListeners()) {
				listener.captureStopped();
			}
		}
	}

	private void closeCaptor() {
		if (_captor != null) {
			_captor.breakloop();
			if (Env.INSTANCE.getOs() != OS.win) {
				try {
					// give it some time, otherwise it crashed the libpcap driver
					Thread.sleep(1000);
				} catch (final InterruptedException e) {
				}
			}
			_captor.close();
			_captor = null;
		}
	}

	/**
	 * @see org.leo.traceroute.core.network.INetworkInterfaceListener#notifyNewNetworkInterface(jpcap.NetworkInterface, byte[])
	 */
	@Override
	public void notifyNewNetworkInterface(final PcapIf device, final byte[] mac) {
		_device = device;
		_localAddresses.clear();
		if (_device != null) {
			for (final PcapAddr add : _device.getAddresses()) {
				try {
					_localAddresses.add(InetAddress.getByAddress(add.getAddr().getData()).getHostAddress());
				} catch (final UnknownHostException e) {
				}
			}
		}
	}

	/**
	 * @see org.leo.traceroute.core.IComponent#dispose()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void dispose() {
		super.dispose();
		endCapture();
		if (_services != null) {
			((INetworkService<PcapIf>) _services.getJnetcapNetwork()).removeListener(this);
		}
		_schedule.shutdown();
	}
}
