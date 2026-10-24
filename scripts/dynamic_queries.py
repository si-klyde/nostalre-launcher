#!/usr/bin/env python3
"""Fully built instances of the queries rAthena assembles at runtime with StringBuf.

extract_queries.py cannot see these (the SQL is built piece by piece in loops), so they
are reproduced here from the builders, with realistic row counts. Output format matches
extract_queries.py's TSV: "<where>\t<sql>".
"""

SLOTS, OPTIONS = 4, 5
CARDS = "".join(f", `card{i}`" for i in range(SLOTS))
OPTION_COLS = "".join(f", `option_id{i}`, `option_val{i}`, `option_parm{i}`" for i in range(OPTIONS))
CARD_VALUES = ", '0'" * SLOTS
OPTION_VALUES = ", '0', '0', '0'" * OPTIONS


def rows(template, count, sep=","):
    return sep.join(template.format(i=i) for i in range(count))


def item_values(owner, inventory):
    # char.cpp memitemdata_to_sql INSERT value tuple; favorite/equip_switch only exist in inventory
    extra = ", '0', '0'" if inventory else ""
    return f"('{owner}', '501', '1', '0', '1', '0', '0', '0', '0', '644245094400000', '0'{extra}{CARD_VALUES}{OPTION_VALUES})"


QUERIES = {
    "char.cpp:409 memo": "INSERT INTO `memo`(`char_id`,`map`,`x`,`y`) VALUES "
        + rows("('150000', 'prontera', '15{i}', '150')", 3),
    "char.cpp:448 skills": "INSERT INTO `skill`(`char_id`,`id`,`lv`,`flag`) VALUES "
        + rows("('150000','{i}','1','0')", 1500),
    "char.cpp:489 friends": "INSERT INTO `friends` (`char_id`, `friend_id`) VALUES "
        + rows("('150000','15000{i}')", 40),
    "char.cpp:513 hotkeys": "REPLACE INTO `hotkey` (`char_id`, `hotkey`, `type`, `itemskill_id`, `skill_lvl`) VALUES "
        + rows("('150000','{i}','0','501','1')", 38),
    "char.cpp:678 item update": "UPDATE `inventory` SET `amount`='5', `equip`='0', `identify`='1', `refine`='0',`attribute`='0', "
        "`expire_time`='0', `bound`='0', `unique_id`='644245094400000', `enchantgrade`='0', `favorite`='0', `equip_switch`='0'"
        + "".join(f", `card{i}`=0" for i in range(SLOTS))
        + "".join(f", `option_id{i}`=0, `option_val{i}`=0, `option_parm{i}`=0" for i in range(OPTIONS))
        + " WHERE `id`='1' LIMIT 1",
    "char.cpp:739 inventory insert": "INSERT INTO `inventory`(`char_id`, `nameid`, `amount`, `equip`, `identify`, `refine`, `attribute`, "
        "`expire_time`, `bound`, `unique_id`, `enchantgrade`, `favorite`, `equip_switch`" + CARDS + OPTION_COLS + ") VALUES "
        + ",".join(item_values(150000, True) for _ in range(100)),
    "char.cpp:739 storage insert": "INSERT INTO `storage`(`account_id`, `nameid`, `amount`, `equip`, `identify`, `refine`, `attribute`, "
        "`expire_time`, `bound`, `unique_id`, `enchantgrade`" + CARDS + OPTION_COLS + ") VALUES "
        + ",".join(item_values(2000000, False) for _ in range(600)),
    "char_mapif.cpp:533 cooldowns": "INSERT INTO `skillcooldown` (`account_id`, `char_id`, `skill`, `tick`) VALUES "
        + rows("('2000000','150000','{i}','5000')", 20, ", "),
    "char_mapif.cpp:977 status changes": "INSERT INTO `sc_data` (`account_id`, `char_id`, `type`, `tick`, `val1`, `val2`, `val3`, `val4`) VALUES "
        + rows("('2000000','150000','{i}','60000','1','0','0','0')", 50, ", "),
    "char_mapif.cpp:1369 bonus scripts": "INSERT INTO `bonus_script` (`char_id`, `script`, `tick`, `flag`, `type`, `icon`) VALUES "
        + rows("('150000','bonus bStr,1;','60000','0','0','-1')", 10, ", "),
    "int_achievement.cpp:137 insert": "INSERT INTO `achievement` (`char_id`, `id`, `completed`, `rewarded`"
        + "".join(f", `count{i}`" for i in range(1, 11)) + ") VALUES ('150000', '1', FROM_UNIXTIME('1700000000'),NULL"
        + ", '0'" * 10 + ")",
    "int_achievement.cpp:172 update": "UPDATE `achievement` SET `completed` = FROM_UNIXTIME('1700000000'),`rewarded` = NULL"
        + "".join(f", `count{i}` = '1'" for i in range(1, 11)) + " WHERE `id` = 1 AND `char_id` = 150000",
    "int_auction.cpp:69 update": "UPDATE `auction` SET `seller_id` = '150000', `seller_name` = ?, `buyer_id` = '0', `buyer_name` = ?, "
        "`price` = '100', `buynow` = '200', `hours` = '24', `timestamp` = '1700000000', `nameid` = '501', `item_name` = ?, "
        "`type` = '0', `refine` = '0', `attribute` = '0', `enchantgrade` = '0'"
        + "".join(f", `card{i}` = '0'" for i in range(SLOTS))
        + "".join(f", `option_id{i}` = '0', `option_val{i}` = '0', `option_parm{i}` = '0'" for i in range(OPTIONS))
        + " WHERE `auction_id` = '1'",
    "int_auction.cpp:109 insert": "INSERT INTO `auction` (`seller_id`,`seller_name`,`buyer_id`,`buyer_name`,`price`,`buynow`,`hours`,"
        "`timestamp`,`nameid`,`item_name`,`type`,`refine`,`attribute`,`unique_id`,`enchantgrade`" + CARDS + OPTION_COLS
        + ") VALUES ('150000',?,'0',?,'100','200','24','1700000000','501',?,'0','0','0','0','0'"
        + ",'0'" * SLOTS + ", '0', '0', '0'" * OPTIONS + ")",
    "int_guild.cpp:577 castle save": "REPLACE INTO `guild_castle` SET `castle_id`='1', `guild_id`='1', `economy`='5', `defense`='5', "
        "`triggerE`='0', `triggerD`='0', `nextTime`='0', `payTime`='0', `createTime`='0', `visibleC`='0'"
        + "".join(f", `visibleG{i}`='0'" for i in range(8)),
    "int_mail.cpp:101 mail": "INSERT INTO `mail` (`send_name`, `send_id`, `dest_name`, `dest_id`, `title`, `message`, `time`, "
        "`status`, `zeny`,`type`) VALUES (?, '150000', ?, '150001', ?, ?, '1700000000', '0', '0', '0')",
    "int_mail.cpp:147 attachments": "INSERT INTO `mail_attachments` (`id`, `index`, `amount`, `nameid`, `refine`, `attribute`, "
        "`identify`, `unique_id`, `bound`, `enchantgrade`" + CARDS + OPTION_COLS + ") VALUES "
        + ",".join(f"('1', '{i}', '1', '501', '0', '0', '1', '0', '0', '0'{CARD_VALUES}{OPTION_VALUES})" for i in range(5)),
    "int_storage.cpp:385 guild bound reset": "UPDATE `char` SET `weapon`='0',`shield`='0',`head_top`='0',`head_mid`='0',"
        "`head_bottom`='0',`robe`='0' WHERE `char_id`='150000'",
    "buyingstore.cpp:247 items": "INSERT INTO `buyingstore_items`(`buyingstore_id`,`index`,`item_id`,`amount`,`price`) VALUES"
        + rows("(1,{i},501,10,50)", 5),
    "log.cpp:242 pick log": "INSERT INTO `picklog` (`time`, `char_id`, `type`, `nameid`, `amount`, `refine`, `map`, `unique_id`, "
        "`bound`, `enchantgrade`" + CARDS + OPTION_COLS
        + ") VALUES(NOW(),'150000','P','501','1','0','prontera','0','0','0'" + ",'0'" * SLOTS + ",'0','0','0'" * OPTIONS + ")",
    "storage.cpp:638 guild storage log": "INSERT INTO `guild_storage_log` (`time`, `guild_id`, `char_id`, `name`, `nameid`, `amount`, "
        "`identify`, `refine`, `attribute`, `unique_id`, `bound`, `enchantgrade`" + CARDS + OPTION_COLS
        + ") VALUES(NOW(),'1','150000', 'Hero', '501', '1','1','0','0','0','0','0'" + ",'0'" * SLOTS + ",'0','0','0'" * OPTIONS + ")",
    "vending.cpp:409 items": "INSERT INTO `vending_items`(`vending_id`,`index`,`cartinventory_id`,`amount`,`price`) VALUES"
        + rows("(1,{i},{i},1,100)", 12),
}

for where, sql in QUERIES.items():
    print(f"dynamic {where}\t{sql}")
