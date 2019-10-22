package com.toien.discover.hc.component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;


import org.apache.http.HttpHost;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.toien.discover.hc.component.HttpProxy.Options;

public class HttpProxyTest {

	private static final Logger logger = LoggerFactory.getLogger(HttpProxyTest.class);

	HttpProxy proxy = null;

	@Before
	public void startUp() {
		proxy = new HttpProxy();
	}

	@Test
	public void testGet() {
		Options<String> options = new HttpProxy.Options<>();
		options.setUri("http://www.baidu.com/");
		String rtValue = proxy.get(options);
		System.out.println(rtValue);
	}

	@Test
	public void testRedirection() throws ClientProtocolException, IOException {
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpClientContext context = HttpClientContext.create();

		HttpGet httpGet = new HttpGet("http://www.toien.com/bangumi/i/2771/");
		httpGet.addHeader(
				"User-Agent",
				"Mozilla/5.0 (Linux; Android 4.2.2; GT-I9505 Build/JDQ39) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.59 Mobile Safari/537.36");

		CloseableHttpResponse response = httpClient.execute(httpGet, context);
		try {
			HttpHost target = context.getTargetHost();
			List<URI> redirectLocations = context.getRedirectLocations();
			URI location = URIUtils.resolve(httpGet.getURI(), target, redirectLocations);
			System.out.println("Final HTTP location: " + location.toASCIIString());
			// Expected to be an absolute URI
		} catch (URISyntaxException e) {
			e.printStackTrace();
		} finally {
			response.close();
		}
	}

	@Test
	public void testLogger() {
		logger.info("throw new RuntimeException()", new RuntimeException());
	}
}
