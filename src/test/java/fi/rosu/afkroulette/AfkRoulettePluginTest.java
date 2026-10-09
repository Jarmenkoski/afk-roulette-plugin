package fi.rosu.afkroulette;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AfkRoulettePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AfkRoulettePlugin.class);
		RuneLite.main(args);
	}
}
