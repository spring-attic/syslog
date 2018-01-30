/*
 * Copyright 2016-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.app.syslog.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.cloud.stream.test.binder.MessageCollector;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.ip.tcp.connection.AbstractServerConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpNetServerConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpNioServerConnectionFactory;
import org.springframework.integration.ip.udp.UnicastReceivingChannelAdapter;
import org.springframework.integration.syslog.inbound.UdpSyslogReceivingChannelAdapter;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.messaging.Message;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Tests for SyslogSource.
 *
 * @author Gary Russell
 * @author Chris Schaefer
 */
@RunWith(SpringRunner.class)
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "syslog.port = 0")
public abstract class SyslogSourceTests {

	protected static final String RFC3164_PACKET = "<157>JUL 26 22:08:35 WEBERN TESTING[70729]: TEST SYSLOG MESSAGE";

	protected static final String RFC5424_PACKET =
			"<14>1 2014-06-20T09:14:07+00:00 loggregator d0602076-b14a-4c55-852a-981e7afeed38 DEA - "
			+ "[exampleSDID@32473 iut=\\\"3\\\" eventSource=\\\"Application\\\" eventID=\\\"1011\\\"]"
			+ "[exampleSDID@32473 iut=\\\"3\\\" eventSource=\\\"Application\\\" eventID=\\\"1011\\\"] "
			+ "Removing instance";

	protected final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	protected Source channels;

	@Autowired
	protected MessageCollector messageCollector;

	@Autowired(required = false)
	protected AbstractServerConnectionFactory connectionFactory;

	@Autowired(required = false)
	protected UdpSyslogReceivingChannelAdapter udpAdapter;

	@Autowired
	protected SyslogSourceProperties properties;

	@Autowired
	protected ApplicationContext context;

	@TestPropertySource(properties = { "syslog.port = 0", "syslog.nio = true", "syslog.reverseLookup = true",
					"syslog.socketTimeout = 123", "syslog.bufferSize = 5" })
	public static class PropertiesPopulatedTests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			assertThat(this.connectionFactory, Matchers.instanceOf(TcpNioServerConnectionFactory.class));
			assertTrue(TestUtils.getPropertyValue(this.connectionFactory, "lookupHost", Boolean.class));
			assertEquals(123, TestUtils.getPropertyValue(this.connectionFactory, "soTimeout"));
			assertEquals(5, TestUtils.getPropertyValue(this.connectionFactory, "deserializer.maxMessageSize"));
		}

	}

	public static class NotNioTests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			assertThat(this.connectionFactory, Matchers.instanceOf(TcpNetServerConnectionFactory.class));
			assertFalse(TestUtils.getPropertyValue(this.connectionFactory, "lookupHost", Boolean.class));
			assertEquals(0, TestUtils.getPropertyValue(this.connectionFactory, "soTimeout"));
			assertEquals(2048, TestUtils.getPropertyValue(this.connectionFactory, "deserializer.maxMessageSize"));
		}

	}

	public static class Tcp3164Tests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			sendTcp(SyslogSourceTests.RFC3164_PACKET + "\n");
			Message<?> syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			Map<?, ?> payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("WEBERN", payload.get("HOST"));
		}

	}

	@TestPropertySource(properties = { "syslog.port = 0", "syslog.rfc = 5424" })
	public static class Tcp5424Tests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			sendTcp("253 " + RFC5424_PACKET);
			Message<?> syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			Map<?, ?> payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("loggregator", payload.get("syslog_HOST"));
		}

	}

	@TestPropertySource(properties = "syslog.protocol = udp")
	public static class Udp3164Tests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			sendUdp(RFC3164_PACKET);
			Message<?> syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			Map<?, ?> payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("WEBERN", payload.get("HOST"));
		}

	}

	@TestPropertySource(properties = { "syslog.protocol = udp", "syslog.rfc = 5424" })
	public static class Udp5424Tests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			sendUdp(RFC5424_PACKET);
			Message<?> syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			Map<?, ?> payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("loggregator", payload.get("syslog_HOST"));
		}

	}

	@TestPropertySource(properties = "syslog.protocol = both")
	public static class TcpAndUdp3164Tests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			sendTcp(SyslogSourceTests.RFC3164_PACKET + "\n");
			Message<?> syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			Map<?, ?> payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("WEBERN", payload.get("HOST"));

			sendUdp(RFC3164_PACKET);
			syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("WEBERN", payload.get("HOST"));
		}

	}

	@TestPropertySource(properties = { "syslog.protocol = both", "syslog.rfc = 5424" })
	public static class TcpAndUdp5424Tests extends SyslogSourceTests {

		@Test
		public void test() throws Exception {
			sendTcp("253 " + RFC5424_PACKET);
			Message<?> syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			Map<?, ?> payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("loggregator", payload.get("syslog_HOST"));

			sendUdp(RFC5424_PACKET);
			syslog = messageCollector.forChannel(channels.output()).poll(10, TimeUnit.SECONDS);
			assertNotNull(syslog);
			assertThat(syslog.getPayload(), Matchers.instanceOf(String.class));
			payload = objectMapper.readValue((String) syslog.getPayload(), Map.class);
			assertEquals("loggregator", payload.get("syslog_HOST"));
		}

	}

	protected void sendTcp(String syslog) throws Exception {
		int port = getPort();
		Socket socket = SocketFactory.getDefault().createSocket("localhost", port);
		socket.getOutputStream().write(syslog.getBytes());
		socket.close();
	}

	protected void sendUdp(String syslog) throws Exception {
		int port = waitUdp();
		DatagramSocket socket = new DatagramSocket();
		DatagramPacket packet = new DatagramPacket(syslog.getBytes(), syslog.length());
		packet.setSocketAddress(new InetSocketAddress("localhost", port));
		socket.send(packet);
		socket.close();
	}

	private int getPort() throws Exception {
		int n = 0;
		while (n++ < 100 && !this.connectionFactory.isListening()) {
			Thread.sleep(100);
		}
		assertTrue("server failed to start listening", this.connectionFactory.isListening());
		int port = this.connectionFactory.getPort();
		assertTrue("server stopped listening", port > 0);
		return port;
	}

	private int waitUdp() throws Exception {
		int n = 0;
		DirectFieldAccessor dfa = new DirectFieldAccessor(this.udpAdapter);
		while (n++ < 100 && !((UnicastReceivingChannelAdapter) dfa.getPropertyValue("udpAdapter")).isListening()) {
			Thread.sleep(100);
		}
		return ((UnicastReceivingChannelAdapter) dfa.getPropertyValue("udpAdapter")).getPort();
	}

	@SpringBootApplication
	public static class SyslogSourceApplication {

	}

}
