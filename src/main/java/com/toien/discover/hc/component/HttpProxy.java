package com.toien.discover.hc.component;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.cookie.ClientCookie;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
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

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.toien.discover.hc.Constants;
import com.toien.discover.hc.util.JsonUtil;

/**
 * 
 * 基于 Apache HTTP Component 的远程接口调用组件
 * 
 * 注：该类并没不是线程安全的，并且在多个线程中调用，需要保持 state-less
 * 
 * @author goku
 *
 */
public class HttpProxy {
	/**
	 * Options 代表每次 HttpProxy 发出请求的候需要使用的参数；包括了 url, headers, cookie, callback
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
		 * Http 请求的头信息
		 */
		private Map<String, String> headers = new HashMap<String, String>();

		/**
		 * 请求中所需要携带的 Cookie
		 */
		private List<Cookie> cookies = new ArrayList<Cookie>();

		/**
		 * 请求完成后后，callback 中的数据的类型，目前支持 String， InputStream；默认为 String
		 * 
		 */
		private Class<?> responseType = String.class;

		/**
		 * 请求中的参数，因为 get 请求的参数可能已经附加在了 url 上，所以在具体处理的时候需要判断
		 */
		private Map<String, Object> parameters = new HashMap<String, Object>();

		/**
		 * 当调用 post 请求的时候，需要根据 contentType 设置 params
		 * 
		 * 可用的值有 APPLICATION_JSON, APPLICATION_FORM_URLENCODED
		 */
		private String contentType = APPLICATION_JSON;

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
		public void parse(HttpGet get) {
			parseBasics(get);
			parseParameters(get);
		}

		/**
		 * 检测 options，并将对应的内容设置到 request 以及 requestContext 对象上
		 * 
		 * @param
		 */
		public void parse(HttpPost post) {

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
			URI reuqestUri = get.getURI();
			String requestQueryString = reuqestUri.getRawQuery();
			StringBuffer paramBuf = new StringBuffer();

			parameters.entrySet().forEach(
					param -> {
						String name = param.getKey();

						if (!Strings.isNullOrEmpty(requestQueryString)
								&& requestQueryString.contains(name + "=")) {
							return; // ignore a parameter if it exist in current
									// uri
						} else {
							paramBuf.append(name).append("=").append(param.getValue()).append("&");
						}

					});
			if (paramBuf.length() > 0) {
				paramBuf.deleteCharAt(paramBuf.length() - 1); // delete last '&'

				String requestUriStr = reuqestUri.toString();
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
				StringEntity entity = new StringEntity(parsed,
						org.apache.http.entity.ContentType.APPLICATION_JSON);
				post.setEntity(entity);

			} else if (APPLICATION_FORM_URLENCODED.equals(contentType)) {

				List<NameValuePair> pairs = new ArrayList<NameValuePair>();

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

			getHeaders().entrySet().forEach(header -> {
				request.setHeader(header.getKey(), header.getValue());
			});

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

		public HttpClientContext getRequestContext() {
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

	/**
	 * 初始化单例的 HttpClient 设置 PoolConnectionManager
	 */
	static {
		RequestConfig requestConfig = RequestConfig.custom()
				.setConnectTimeout(CONNECT_TIMEOUT)
				.setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT)
				.setSocketTimeout(SOCKET_TIMEOUT).build();
		PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
		// Increase max total connection to 200
		connManager.setMaxTotal(400);
		// Increase default max connection per route to 20
		connManager.setDefaultMaxPerRoute(90);

		HttpHost proxy = new HttpHost("localhost", 8888);
		DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);

		CLIENT = HttpClients.custom().setConnectionManager(connManager)
				.setDefaultRequestConfig(requestConfig).setRoutePlanner(routePlanner).build();
	}

	private static final Logger logger = LoggerFactory.getLogger(HttpProxy.class);

	/**
	 * 处理简单的 Http Get 请求，根据 options 中的参数设置 返回不同类型的值，目前支持 InputStream，String
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
			// if context is null, will request use default context
			CloseableHttpResponse response = CLIENT.execute(get, options.getRequestContext());
			respEntity = response.getEntity();

			if (InputStream.class.equals(options.getResponseType())) {
				returnValue = options.callback.apply(respEntity.getContent());
			} else if (String.class.equals(options.getResponseType())) {
				String resp = EntityUtils.toString(respEntity, "UTF-8");
				returnValue = options.callback.apply(resp);
			}
			
//			logger.info("GET request:{}, response:{}" , options.getUri(), JsonUtil.toJson(returnValue, false)); TODO Options 可配置

		} catch (IOException e) {
			logger.error("get 时发生异常 {}", new Object[] { e, options.toString() });
		} finally {
			try {
				EntityUtils.consume(respEntity);
			} catch (IOException e) {
				logger.error("consume 时发生异常 {}", new Object[] { e, options.toString() });
			}
		}

		return returnValue;
	}
	
	/**
	 * 记得 close response, 或者 EntityUtils.consume(respEntity);
	 * 
	 * @param request
	 * @return
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	public CloseableHttpResponse execute(HttpUriRequest request) throws ClientProtocolException, IOException {
		return CLIENT.execute(request);
	}

	/**
	 * 处理 Http Post 请求，根据 options 中的参数设置 返回不同类型的值，目前支持 InputStream，String
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

			// if context is null, will request use default context
			CloseableHttpResponse response = CLIENT.execute(post, options.getRequestContext());
			respEntity = response.getEntity();

			if (InputStream.class.equals(options.getResponseType())) {
				returnValue = options.callback.apply(respEntity.getContent());

			} else if (String.class.equals(options.getResponseType())) {
				String resp = EntityUtils.toString(respEntity, "UTF-8");
				returnValue = options.callback.apply(resp);
			}
			
//			logger.info("POST request:{}, response:{}" , options.getUri(), JsonUtil.toJson(returnValue, false)); TODO Options 可配置
		} catch (IOException e) {
			logger.error("post 时发生异常 {} ", new Object[] { e, options.toString() });
		} finally {
			try {
				EntityUtils.consume(respEntity);
			} catch (IOException e) {
				logger.error("consume 时发生异常 {} ", new Object[] { e, options.toString() });
			}
		}

		return returnValue;
	}

	/**
	 * 将调用接口时的参数转成 URL 中的 QueryString 形式
	 * 
	 * <p>
	 * map: { name=toien, age=11, rank=3000 } ==> name=toien&age=11&rank=3000
	 * 
	 * @param params
	 * @return
	 */
	protected String transferToQueryString(Map<String, Object> params) {
		StringBuffer buffer = new StringBuffer();

		for (Entry<String, Object> entry : params.entrySet()) {
			buffer.append(entry.getKey()).append("=").append(entry.getValue().toString())
					.append("&");
		}

		buffer.deleteCharAt(buffer.length() - 1); // delete last '&'

		return buffer.toString();
	}

	/**
	 * 以 POST multipart/form-data 上传图片文件，图片文件位于 form data 的 field 中
	 * 
	 * @param url
	 *            接口 URI
	 * @param file
	 *            待上传的文件
	 * @param fileFieldName
	 *            form-data 中 文件属性对应的表单名称
	 * @return 接口调用后的返回值 { code : 0 uri: "http://xxx/yy.jpg" }
	 */
	public String uploadByPost(String url, File file, String fileFieldName) {
		if (!file.exists()) {
			return null;
		}

		MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();

		HttpPost httpPost = new HttpPost(url);

		FileBody fileBody = new FileBody(file);

		HttpEntity reqEntity = entityBuilder.addPart(fileFieldName, fileBody).build();
		httpPost.setEntity(reqEntity);

		String returnValue = null;
		try (CloseableHttpResponse response = CLIENT.execute(httpPost)) {

			HttpEntity respEntity = response.getEntity();

			returnValue = EntityUtils.toString(respEntity, "UTF-8");
			
			logger.info("uploadByPost file:{}, response:{}", file, returnValue);
			EntityUtils.consume(respEntity);
		} catch (IOException e) {
			logger.error("uploadByPost 上传文件时发生异常 {} {}", url, e);
		}

		return returnValue;
	}

	/**
	 * 以 PUT image/xxx 上传图片文件，图片文件位于 request body 中
	 * 
	 * @param url
	 * @param file
	 * @return
	 */
	public String uploadByPut(String url, File file) {
		if (!file.exists()) {
			return null;
		}

		HttpPut put = new HttpPut(url);
		String contentType = "";
		String fileName = file.getName();
		switch (fileName.substring(fileName.lastIndexOf(".") + 1)) {
			case "jpg":
			case "jpeg":
				contentType = "image/jpeg";
				break;
			case "png":
				contentType = "image/png";
				break;
			case "gif":
				contentType = "image/gif";
				break;
			case "bmp":
				contentType = "image/bmp";
				break;
			case "webp":
				contentType = "image/webp";
				break;
			default: {
				throw new IllegalArgumentException("uploadByPut 中文件类型错误:" + file.getName());
			}
		}
		put.addHeader("Content-Type", contentType);

		FileEntity reqEntity = new FileEntity(file);
		put.setEntity(reqEntity);

		String returnValue = null;
		try (CloseableHttpResponse response = CLIENT.execute(put)) {

			HttpEntity respEntity = response.getEntity();

			returnValue = EntityUtils.toString(respEntity, "UTF-8");
			
			logger.info("uploadByPut file:{}, response:{}", file, returnValue);
			EntityUtils.consume(respEntity);
		} catch (IOException e) {
			logger.error("uploadByPut 上传文件时发生异常 {} {}", url, e);
		}

		return returnValue;
	}

}
