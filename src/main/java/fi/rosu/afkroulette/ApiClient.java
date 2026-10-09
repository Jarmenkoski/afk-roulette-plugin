package fi.rosu.afkroulette;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the AFK Roulette server. Every call is asynchronous (OkHttp's own
 * thread pool) and the callback runs on that pool, never on the client thread.
 */
@Slf4j
@Singleton
public class ApiClient
{
	public static final String BASE_URL = "https://afk-api.rosu.fi";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	public interface Callback
	{
		/**
		 * @param body  parsed JSON body (may be present also on errors)
		 * @param error null on success, otherwise a short human readable reason
		 */
		void onResult(JsonObject body, String error);
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final AfkRouletteConfig config;

	@Inject
	ApiClient(OkHttpClient http, Gson gson, AfkRouletteConfig config)
	{
		this.http = http;
		this.gson = gson;
		this.config = config;
	}

	public void get(String path, Map<String, String> query, Callback cb)
	{
		get(path, query, null, cb);
	}

	/** GET with an explicit group token instead of the configured one (e.g. to check a token before saving it). */
	public void get(String path, Map<String, String> query, String tokenOverride, Callback cb)
	{
		HttpUrl base = HttpUrl.parse(BASE_URL + path);
		if (base == null)
		{
			cb.onResult(null, "Bad URL");
			return;
		}
		HttpUrl.Builder url = base.newBuilder();
		if (query != null)
		{
			query.forEach(url::addQueryParameter);
		}
		send(new Request.Builder().url(url.build()).get(), tokenOverride, cb);
	}

	public void post(String path, Object body, Callback cb)
	{
		RequestBody requestBody = RequestBody.create(JSON, gson.toJson(body));
		send(new Request.Builder().url(BASE_URL + path).post(requestBody), null, cb);
	}

	private void send(Request.Builder request, String tokenOverride, Callback cb)
	{
		if (!config.serverEnabled())
		{
			cb.onResult(null, "Server connection is off. Turn it on in the plugin settings.");
			return;
		}
		String token = tokenOverride != null ? tokenOverride.trim() : config.groupToken().trim();
		if (!token.isEmpty())
		{
			request.header("Authorization", token);
		}
		request.header("Accept", "application/json");

		http.newCall(request.build()).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("AFK Roulette request failed", e);
				cb.onResult(null, "Could not reach the server");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody responseBody = response.body())
				{
					String text = responseBody != null ? responseBody.string() : "";
					JsonObject json = parse(text);
					if (response.isSuccessful())
					{
						cb.onResult(json, json == null ? "Unexpected server response" : null);
					}
					else
					{
						String error = json != null && json.has("error")
							? json.get("error").getAsString()
							: "Server error " + response.code();
						cb.onResult(json, error);
					}
				}
				catch (IOException e)
				{
					log.debug("AFK Roulette response read failed", e);
					cb.onResult(null, "Could not read the server response");
				}
			}
		});
	}

	private JsonObject parse(String text)
	{
		try
		{
			return gson.fromJson(text, JsonObject.class);
		}
		catch (JsonParseException e)
		{
			return null;
		}
	}
}
