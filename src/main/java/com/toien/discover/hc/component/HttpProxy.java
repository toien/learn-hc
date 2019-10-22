package com.toien.discover.hc.component;


import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.toien.discover.hc.Constants;
import com.toien.discover.hc.util.JsonUtil;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.cookie.ClientCookie;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 
 * 基于 Apache HTTP Component 的远程接口调用组件
 * 已配置 http 链接池，超时，采用 UTF-8 编码;
 * 支持 cookie，contentType 设置，返回值反序列化为自定义类型，callback;
 * 只实现了 get/post 请求，可自由扩展;
 * 
 * @author goku
 *
 */
public class HttpProxy {
	/**
	 * Options 代表每次 HttpProxy 发出请求的候需要使用的参数；包括了 uri, headers, cookie, callback
	 * 它是一个泛型类。
	 * 
	 * @param <R>
	 *            R 表示欲将返回数据解析后的类型，即 get/post 方法调用的返回类型。
	 */
	public static class Options<R> {
		public static final String APPLICATION_JSON = "application/json";
		public static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";
		public static final String CHARSET = "UTF-8";

		/**
		 * 请求地址
		 */
		private String uri;

		/**
		 * 请求中的参数，因为 get 请求的参数可能已经附加在了 url 上，所以在具体处理的时候需要判断
		 */
		private Map<String, Object> parameters = new HashMap<>();

		/**
		 * 当调用 post 请求的时候，需要根据 contentType 设置 params
		 *
		 * 可用的值有 APPLICATION_JSON, APPLICATION_FORM_URLENCODED
		 */
		private String contentType = APPLICATION_JSON;

		/**
		 * Http 请求的头信息
		 */
		private Map<String, String> headers = new HashMap<>();

		/**
		 * 请求中所需要携带的 Cookie
		 */
		private List<Cookie> cookies = new ArrayList<>();

		/**
		 * 请求完成后后，callback 中的数据的类型，目前支持 String， InputStream；默认为 String
		 * 
		 */
		private Class<?> responseType = String.class;

		/**
		 * 每次请求的时候的 context，调用 options.parse 会产生，在每次请求之前，HttpProxy 都会调用
		 * Options.parse 方法来确保请求有效
		 */
		private HttpClientContext requestContext;

		/**
		 * callback 是请求结束后的回调函数，一般用来 parse 数据；如果未定义，则直接返回 response；
		 * 
		 * @throws ClassCastException
		 */
		@SuppressWarnings("unchecked")
		private Function<Object, R> callback = (input -> (R) input);

		public Options<R> addCookie(Cookie cookie) {
			cookies.add(cookie);
			return this;
		}
		
		public Options<R> addCookies(List<Cookie> cookies) {
			this.cookies.addAll(cookies);
			return this;
		}

		public Options<R> addHeader(String name, String value) {
			headers.put(name, value);
			return this;
		}

		public Function<Object, R> getCallback() {
			return callback;
		}

		public List<Cookie> getCookies() {
			return cookies;
		}

		public Map<String, String> getHeaders() {
			return headers;
		}

		public Class<?> getResponseType() {
			return responseType;
		}

		public String getUri() {
			return uri;
		}

		public Options<R> setCallback(Function<Object, R> callback) {
			this.callback = callback;
			return this;
		}

		public Options<R> setCookies(List<Cookie> cookies) {
			this.cookies = cookies;
			return this;
		}

		public Options<R> setHeaders(Map<String, String> headers) {
			this.headers = headers;
			return this;
		}

		/**
		 * 
		 * 设置 response 的类型，HttpClient 从 Socket 读取原始数据后需要转换成类型；
		 * <p>
		 * 转换成该对应的类型的数据作为 callback 函数的输入参数；
		 * 
		 * @param responseType
		 *            目前支持 InputStream.class 和 String.class 两种
		 * @return this
		 */
		public Options<R> setResponseType(Class<?> responseType) {
			this.responseType = responseType;
			return this;
		}

		public Options<R> setUri(String uri) {
			this.uri = uri;
			return this;
		}

		public Options<R> addParameter(String name, Object value) {
			this.parameters.put(name, value);
			return this;
		}

		public Options<R> addParameters(Map<String, Object> params) {
			this.parameters.putAll(params);
			return this;
		}

		@Override
		public String toString() {
			return String.format("url:%s  hearders:%s", uri, headers.toString());
		}

		/**
		 * 检测 options，并将对应的内容设置到 request 以及 requestContext 对象上
		 * 
		 * @param get
		 */
		private void parse(HttpGet get) {
			parseBasics(get);
			parseParameters(get);
		}

		/**
		 * 检测 options，并将对应的内容设置到 request 以及 requestContext 对象上
		 * 
		 * @param
		 */
		private void parse(HttpPost post) {
			parseBasics(post);
			parseParameters(post);
		}

		/**
		 * 将 options 中的参数解析到 get 请求上，根据情况判断是否附加到 URI 后面
		 * 
		 * @param get
		 */
		private void parseParameters(HttpGet get) {
			if (parameters.keySet().isEmpty()) {
				return;
			}
			URI requestUri = get.getURI();
			String requestQueryString = requestUri.getRawQuery();
			StringBuffer paramBuf = new StringBuffer();

			parameters.forEach((name, value) -> {
                if (!Strings.isNullOrEmpty(requestQueryString) && requestQueryString.contains(name + "=")) {
                    return; // ignore a parameter if it exist in current uri
                } else {
                    paramBuf.append(name).append("=").append(value).append("&");
                }
            });

			if (paramBuf.length() > 0) {
				paramBuf.deleteCharAt(paramBuf.length() - 1); // delete last '&'

				String requestUriStr = requestUri.toString();
				if (requestQueryString == null) {
					get.setURI(URI.create(requestUriStr.concat("?").concat(paramBuf.toString())));
				} else if (requestQueryString.isEmpty()) {
					get.setURI(URI.create(requestUriStr.concat(paramBuf.toString())));
				} else {
					get.setURI(URI.create(requestUriStr.concat("&").concat(paramBuf.toString())));
				}
			}
		}

		/**
		 * 根据 contentType 决定如何将参数设置到 post 上
		 * 
		 * @param post
		 */
		private void parseParameters(HttpPost post) {
			if (parameters.keySet().isEmpty()) {
				return;
			}

			post.setHeader("Content-Type", contentType);

			if (APPLICATION_JSON.equals(contentType)) {
				String parsed = JsonUtil.toJson(parameters, false);
				StringEntity entity = new StringEntity(parsed, org.apache.http.entity.ContentType.APPLICATION_JSON);
				post.setEntity(entity);

			} else if (APPLICATION_FORM_URLENCODED.equals(contentType)) {
				List<NameValuePair> pairs = new ArrayList<>();

				for (Map.Entry<String, Object> entry : parameters.entrySet()) {
					String key = entry.getKey();
					String value = String.valueOf(entry.getValue());
					pairs.add(new BasicNameValuePair(key, value));
				}

				try {
					post.setEntity(new UrlEncodedFormEntity(pairs, CHARSET));
				} catch (UnsupportedEncodingException e) {
					logger.error("HttpProxy.Options.parseParameters 设置参数时异常", e);
				}
			}
		}

		/**
		 * 检测解析设置 uri，header，cookie
		 * 
		 * @param request
		 *            HttpGet / HttpPost 的父类
		 */
		private void parseBasics(HttpRequestBase request) {
			Preconditions.checkNotNull(getUri());

			request.setURI(URI.create(uri));

			getHeaders().forEach(request::setHeader);

			parseCookies();
		}

		private void parseCookies() {
			if (getCookies().isEmpty()) {
				return;
			}

			if (requestContext == null) {
				requestContext = HttpClientContext.create();
			}

			CookieStore cookieStore = new BasicCookieStore();
			for (Cookie cookie : getCookies()) {
				BasicClientCookie apacheCookie = new BasicClientCookie(cookie.getName(),
						cookie.getValue());
				
				if(cookie.getDomain() == null) { // 如果传入的 Cookie 没有设置 domain，则用 ".toien.com" 
					apacheCookie.setDomain(Constants.DEFAULT_COOKIE_DOMAIN);
					apacheCookie.setAttribute(ClientCookie.DOMAIN_ATTR, Constants.DEFAULT_COOKIE_DOMAIN);
				} else {
					apacheCookie.setDomain(cookie.getDomain());
					apacheCookie.setAttribute(ClientCookie.DOMAIN_ATTR, cookie.getDomain());
				}
				
				if(cookie.getPath() == null) { // 如果传入的 Cookie 没有设置 path，则用 "/"
					apacheCookie.setPath("/");
					apacheCookie.setAttribute(ClientCookie.PATH_ATTR, "/");
				} else {
					apacheCookie.setPath(cookie.getPath());
					apacheCookie.setAttribute(ClientCookie.PATH_ATTR, cookie.getPath());
				}

				cookieStore.addCookie(apacheCookie);
			}
			// will replace all cookies by call parseCookies
			requestContext.setCookieStore(cookieStore);
		}

		private HttpClientContext getRequestContext() {
			return requestContext;
		}

		public Options<R> setContentType(String contentType) {
			this.contentType = contentType;
			return this;
		}
	}

	/**
	 * Singleton Client
	 */
	private static final CloseableHttpClient CLIENT;

	/**
	 * The timeout in milliseconds used when requesting a connection
     * from the connection manager.
	 */
	private static final int CONNECTION_REQUEST_TIMEOUT = 1000;

	/**
	 * Determines the timeout in milliseconds until a connection is established.
	 */
	private static final int CONNECT_TIMEOUT = 1000;

	/**
	 * Defines the socket timeout ({@code SO_TIMEOUT}) in milliseconds,
     * which is the timeout for waiting for data  or, put differently,
     * a maximum period inactivity between two consecutive data packets).
	 */
	private static final int SOCKET_TIMEOUT = 3000;

	private static final int POOL_MAX_SIZE = 100;

	private static final int POOL_MAX_PER_ROUTE = 30;

	private static final int CONNECTION_TTL_SECONDS = 60;

	private static final int CONNECTION_VALIDATE_AFTER_INACTIVITY_SECONDS = 30;

	/*
	 * 初始化单例的 HttpClient 设置 PoolConnectionManager
	 */
	static {
		RequestConfig requestConfig = RequestConfig.custom()
				.setConnectTimeout(CONNECT_TIMEOUT)
				.setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT)
				.setSocketTimeout(SOCKET_TIMEOUT).build();

		PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(CONNECTION_TTL_SECONDS, TimeUnit.SECONDS);
		connManager.setMaxTotal(POOL_MAX_SIZE);
		connManager.setDefaultMaxPerRoute(POOL_MAX_PER_ROUTE);
		connManager.setValidateAfterInactivity(CONNECTION_VALIDATE_AFTER_INACTIVITY_SECONDS);

		HttpHost proxy = new HttpHost("localhost", 8888);
		DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);

		CLIENT = HttpClients.custom()
                .setConnectionManager(connManager)
				.setDefaultRequestConfig(requestConfig)
                // .setRoutePlanner(routePlanner)
                .build();
	}

	private static final Logger logger = LoggerFactory.getLogger(HttpProxy.class);

	private <R> R doExecute(HttpRequestBase request, Options<R> options) throws IOException {
	    // if context is null, will request use default context
        CloseableHttpResponse response = CLIENT.execute(request, options.getRequestContext());
        HttpEntity respEntity = response.getEntity();

        R returnValue = null;
        if (InputStream.class.equals(options.getResponseType())) {
            returnValue = options.callback.apply(respEntity.getContent());
        } else if (String.class.equals(options.getResponseType())) {
            String resp = EntityUtils.toString(respEntity, "UTF-8");
            returnValue = options.callback.apply(resp);
        }

        return returnValue;
    }

	/**
	 * 处理 Get 请求，根据 options 中的参数设置返回不同类型的值，目前支持 InputStream，String
	 * 
	 * @param options
	 * @return
	 */
    public <R> R get(Options<R> options) {
		HttpGet get = new HttpGet();
		options.parse(get);

		HttpEntity respEntity = null;
		R returnValue = null;
		try {
			returnValue = doExecute(get, options);
		} catch (IOException e) {
			logger.error("get 时发生异常, err:{}, options:{} ", e, options.toString());
		} finally {
			try {
				EntityUtils.consume(respEntity);
			} catch (IOException e) {
				logger.error("consume 时发生异常, err:{}, options:{} ", e, options.toString());
			}
		}

		return returnValue;
	}
	
	/**
	 * 处理 Post 请求，根据 options 中的参数设置返回不同类型的值，目前支持 InputStream，String
	 * 
	 * @param options
	 * @return
	 */
	public <R> R post(Options<R> options) {
		HttpPost post = new HttpPost();
		options.parse(post);

		HttpEntity respEntity = null;
		R returnValue = null;
		try {
			returnValue = doExecute(post, options);
		} catch (IOException e) {
			logger.error("post 时发生异常, err:{}, options:{} ", e, options.toString());
		} finally {
			try {
				EntityUtils.consume(respEntity);
			} catch (IOException e) {
				logger.error("consume 时发生异常, err:{}, options:{} ", e, options.toString());
			}
		}

		return returnValue;
	}
}
