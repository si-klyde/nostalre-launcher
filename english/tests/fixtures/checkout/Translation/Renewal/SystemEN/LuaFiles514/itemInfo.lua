tbl = {
	[501] = {
		unidentifiedDisplayName = "Red Potion",
		unidentifiedResourceName = "red_potion",
		unidentifiedDescriptionName = { "A red potion." },
		identifiedDisplayName = "Red Potion (Renewal)",
		identifiedResourceName = "red_potion",
		identifiedDescriptionName = {
			"Renewal text",
			"Heals #HP#"
		},
		slotCount = 0,
		ClassNum = 0
	},
	-- a comment between entries
	[502] = {
		unidentifiedDisplayName = "Orange Potion";
		unidentifiedResourceName = "orange_potion";
		unidentifiedDescriptionName = {};
		identifiedDisplayName = "Orange Potion";
		identifiedResourceName = "orange_potion";
		identifiedDescriptionName = { "Orange \"juice\"", "Line\twith tab #2" };
		slotCount = 0;
		ClassNum = 0
	}
}

function main()
	for ItemID, DESC in pairs(tbl) do
		local result, msg = AddItem(ItemID, DESC.unidentifiedDisplayName)
	end
	return true, "good"
end
