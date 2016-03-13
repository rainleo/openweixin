package com.itranswarp.wxapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.itranswarp.wxapi.bean.IpList;
import com.itranswarp.wxapi.bean.MaterialCount;
import com.itranswarp.wxapi.exception.WeixinException;
import com.itranswarp.wxapi.exception.WeixinSecurityException;
import com.itranswarp.wxapi.token.AccessToken;
import com.itranswarp.wxapi.token.AccessTokenCache;
import com.itranswarp.wxapi.util.HashUtil;
import com.itranswarp.wxapi.util.HttpUtil;
import com.itranswarp.wxapi.util.JsonUtil;
import com.itranswarp.wxapi.util.MapUtil;
import com.itranswarp.wxapi.util.XmlUtil;

@Component
public class WeixinClient {

	@Value("${wxapi.app.id}")
	String appId;

	@Value("${wxapi.app.secret}")
	String appSecret;

	@Value("${wxapi.app.token}")
	String appToken;

	@Value("${wxapi.app.aeskey}")
	String strAesKey;

	byte[] aesKey;
	byte[] ivParamSpec;

	final Log log = LogFactory.getLog(getClass());

	static final String WEIXIN_URL = "https://api.weixin.qq.com/cgi-bin";

	static final long REFRESH_TOKEN_IN_MILLIS = 3600 * 1000L;

	@Value("${wxapi.debug:true}")
	boolean debug;

	@Autowired
	AccessTokenCache cache;

	@PostConstruct
	public void init() {
		// init AES key:
		if (this.strAesKey == null || this.strAesKey.length() != 43) {
			throw new IllegalArgumentException("Invalid property: weixin.app.aeskey");
		}
		aesKey = Base64.getDecoder().decode(strAesKey);
		ivParamSpec = Arrays.copyOfRange(aesKey, 0, 16);
		// get access token immediately:
		refreshAccessToken();
	}

	// Access token ///////////////////////////////////////////////////////////

	// refresh access token by timer:
	@Scheduled(initialDelay = REFRESH_TOKEN_IN_MILLIS, fixedRate = REFRESH_TOKEN_IN_MILLIS)
	void refreshAccessToken() {
		log.info("Refresh access token...");
		AccessToken at = getAccessToken();
		log.info("Access toekn was successfully refreshed.");
		cache.setAccessToken(at.access_token, at.expires_in);
	}

	// get access token from weixin:
	AccessToken getAccessToken() {
		return getJson(AccessToken.class, "/token",
				MapUtil.createMap("appid", appId, "secret", appSecret, "grant_type", "client_credential"));
	}

	// Weixin API /////////////////////////////////////////////////////////////

	/**
	 * 验证来自微信服务器的请求是否合法
	 * 
	 * @param request
	 *            The HttpServletRequest object.
	 */
	public void validateSignature(HttpServletRequest request) {
		String timestamp = request.getParameter("timestamp");
		validateTimestamp(timestamp);
		String nonce = request.getParameter("nonce");
		String signature = request.getParameter("signature");
		String[] arr = { this.appToken, nonce, timestamp };
		Arrays.sort(arr);
		String sha1 = HashUtil.sha1(String.join("", arr));
		if (!sha1.equals(signature)) {
			throw new WeixinSecurityException("Bad signature");
		}
	}

	void validateTimestamp(String timestamp) {
		long ts = Long.parseLong(timestamp);
		if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 60) {
			throw new WeixinException("Bad timestamp");
		}
	}

	/**
	 * API: 获取微信服务器IP地址
	 * 
	 * @return IpList object.
	 */
	public IpList getIpList() {
		return getJson(IpList.class, "/getcallbackip", MapUtil.createMap("access_token", cache.getAccessToken()));
	}

	/**
	 * API: 获取素材总数
	 * 
	 * @return MaterialCount object.
	 */
	public MaterialCount materialCount() {
		return getJson(MaterialCount.class, "/material/get_materialcount",
				MapUtil.createMap("access_token", cache.getAccessToken()));
	}

	public <T> T getJson(Class<T> clazz, String uri, Map<String, String> data) {
		String json;
		try {
			json = HttpUtil.httpGet(WEIXIN_URL + uri, data, null);
		} catch (IOException e) {
			throw new WeixinException(e);
		}
		if (debug) {
			log.info("Weixin >>> " + json);
		}
		if (json.contains("\"errcode\"")) {
			JsonError err = JsonUtil.fromJson(JsonError.class, json);
			throw new WeixinException(err.errcode, err.errmsg);
		}
		return JsonUtil.fromJson(clazz, json);
	}

	static final Map<String, String> CONTENT_TYPE_JSON = MapUtil.createMap("Content-Type", "application/json");

	static final Map<String, String> CONTENT_TYPE_WWW_FORM = MapUtil.createMap("Content-Type",
			"application/x-www-form-urlencoded");

	static final Map<String, String> CONTENT_TYPE_MULTIPART = MapUtil.createMap("Content-Type", "multipart/form-data");

	public <T> T postJson(Class<T> clazz, String uri, Object data) {
		String json;
		try {
			json = HttpUtil.httpPost(WEIXIN_URL + uri, JsonUtil.toJson(data), CONTENT_TYPE_JSON);
		} catch (IOException e) {
			throw new WeixinException(e);
		}
		if (debug) {
			log.info("Weixin >>> " + json);
		}
		if (json.contains("\"errcode\"")) {
			JsonError err = JsonUtil.fromJson(JsonError.class, json);
			if (err.errcode != 0) {
				throw new WeixinException(err.errcode, err.errmsg);
			}
		}
		return JsonUtil.fromJson(clazz, json);
	}

	public <T> T postForm(Class<T> clazz, String uri, Map<String, Object> params) {
		InputStream data = encodeFormData(params);
		String json;
		try {
			json = HttpUtil.httpPost(WEIXIN_URL + uri, data, null);
		} catch (IOException e) {
			throw new WeixinException(e);
		}
		if (debug) {
			log.info("Weixin >>> " + json);
		}
		if (json.contains("\"errcode\"")) {
			JsonError err = JsonUtil.fromJson(JsonError.class, json);
			if (err.errcode != 0) {
				throw new WeixinException(err.errcode, err.errmsg);
			}
		}
		return JsonUtil.fromJson(clazz, json);
	}

	InputStream encodeFormData(Map<String, Object> params) {
		// TODO Auto-generated method stub
		return null;
	}

	// AES decryption /////////////////////////////////////////////////////////

	/**
	 * Read XML from request. The returned XML is decrypted if AES-encryption is
	 * enabled.
	 * 
	 * @param request
	 *            The HttpServletRequest object.
	 * @return The XML as string.
	 * @throws IOException
	 *             If IO error.
	 */
	public String readXml(HttpServletRequest request) throws IOException {
		String xml = null;
		String data = readAsString(request.getInputStream());
		String encrypt_type = request.getParameter("encrypt_type");
		if ("aes".equals(encrypt_type)) {
			EncryptedMessage encryptedMsg = XmlUtil.fromXml(EncryptedMessage.class, data);
			log.info(encryptedMsg.Encrypt);
			xml = decryptMessage(request, encryptedMsg.Encrypt);
		} else {
			xml = data;
		}
		return xml;
	}

	String readAsString(ServletInputStream inputStream) throws IOException {
		byte[] buffer = new byte[1024];
		ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
		for (;;) {
			int n = inputStream.read(buffer);
			if (n == (-1)) {
				break;
			}
			output.write(buffer, 0, n);
		}
		return output.toString("UTF-8");
	}

	String decryptMessage(HttpServletRequest request, String encryptedData) {
		String timestamp = request.getParameter("timestamp");
		validateTimestamp(timestamp);
		String nonce = request.getParameter("nonce");
		String msg_signature = request.getParameter("msg_signature");
		String[] arr = { this.appToken, nonce, timestamp, encryptedData };
		Arrays.sort(arr);
		String sha1 = HashUtil.sha1(String.join("", arr));
		if (!sha1.equals(msg_signature)) {
			throw new WeixinSecurityException("Bad signature");
		}
		try {
			return decrypt(encryptedData);
		} catch (GeneralSecurityException e) {
			throw new WeixinException(e);
		}
	}

	String decrypt(String data) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
		SecretKeySpec keySpec = new SecretKeySpec(this.aesKey, "AES");
		IvParameterSpec iv = new IvParameterSpec(this.ivParamSpec);
		cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);
		byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(data));
		byte[] bytes = pkcs7Decode(decrypted);
		byte[] networkOrder = Arrays.copyOfRange(bytes, 16, 20);
		int xmlLength = recoverNetworkBytesOrder(networkOrder);
		String fromAppId = new String(Arrays.copyOfRange(bytes, 20 + xmlLength, bytes.length), StandardCharsets.UTF_8);
		if (!fromAppId.equals(this.appId)) {
			throw new WeixinSecurityException("Invalid app id");
		}
		return new String(Arrays.copyOfRange(bytes, 20, 20 + xmlLength), StandardCharsets.UTF_8);
	}

	boolean isAESEncryptedMessage(HttpServletRequest request) {
		return "aes".equals(request.getParameter("encrypt_type"));
	}

	static byte[] pkcs7Decode(byte[] decrypted) {
		int pad = (int) decrypted[decrypted.length - 1];
		if (pad < 1 || pad > 32) {
			pad = 0;
		}
		return Arrays.copyOfRange(decrypted, 0, decrypted.length - pad);
	}

	static int recoverNetworkBytesOrder(byte[] orderBytes) {
		int sourceNumber = 0;
		for (int i = 0; i < 4; i++) {
			sourceNumber <<= 8;
			sourceNumber |= orderBytes[i] & 0xff;
		}
		return sourceNumber;
	}

	// Static JavaBean for JSON ///////////////////////////////////////////////

	public static class JsonError {
		public int errcode;
		public String errmsg;
	}

	public static class EncryptedMessage {
		public String ToUserName;
		public String Encrypt;
	}

}