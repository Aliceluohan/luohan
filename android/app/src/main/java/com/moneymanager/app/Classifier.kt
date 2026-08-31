package com.moneymanager.app

/**
 * 和网页里 RULES / INCOME_RULES 保持一致的关键词分类表。
 * 用 List<Pair> 而不是 Map，是为了保留和网页一样的遍历顺序——
 * 多个关键词同时命中、长度又相等时，谁排在前面谁赢，两边必须一致。
 */
object Classifier {

    private val EXPENSE_RULES: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("瑞幸","星巴克","库迪","蜜雪","喜茶","奈雪","霸王茶姬","美团","饿了么","肯德基","麦当劳","必胜客","汉堡","海底捞","餐","饭","食","咖啡","奶茶","茶饮","火锅","烧烤","烤肉","面馆","米线","粉","小吃","外卖","食堂","酒楼","餐厅","厨","bakery","面包","蛋糕","便当","寿司","拉面","coffee","cafe"),
        "交通" to listOf("地铁","公交","轨道交通","滴滴","高德","花小猪","出行","打车","网约车","加油","中石化","中石油","停车","火车","12306","铁路","航空","机票","东航","南航","国航","单车","哈啰","青桔","美团单车","ETC","高速","客运","轮渡","租车"),
        "购物" to listOf("淘宝","天猫","京东","拼多多","阿里巴巴","唯品会","得物","闲鱼","小米","苹果","Apple","华为","旗舰店","商贸","服饰","鞋","箱包","数码","家具","宜家","优衣库","ZARA","网购","抖音商城","快手小店"),
        "日用" to listOf("超市","便利","7-ELEVEN","全家","罗森","便利蜂","盒马","永辉","沃尔玛","山姆","大润发","华润万家","菜市","生鲜","水果","屈臣氏","日化","洗护","纸巾","洗衣","美团买菜","多多买菜","叮咚"),
        "娱乐" to listOf("影院","电影","万达影","CGV","腾讯视频","爱奇艺","优酷","芒果TV","哔哩哔哩","B站","Steam","游戏","网易游戏","王者","原神","KTV","酒吧","健身","运动","网易云音乐","QQ音乐","Spotify","剧本杀","密室","展览","演出","门票","旅游","酒店","民宿","携程","飞猪","Netflix"),
        "居住" to listOf("房租","租金","物业","水费","电费","燃气","供暖","国家电网","自来水","燃气公司","公寓","中介","押金"),
        "通信" to listOf("移动","联通","电信","话费","充值","流量","宽带","手机费","虚拟运营"),
        "医疗" to listOf("医院","药房","大药房","药店","诊所","体检","口腔","牙","眼科","挂号","医保","妇幼","中医","康复","眼镜"),
        "学习" to listOf("图书","书店","当当","博库","课程","学费","教育","培训","考试","报名费","知识","得到","极客","慕课","文具","打印","复印","订阅","ChatGPT","Claude","Adobe","Figma","Notion","会员"),
        "转账" to listOf("转账","红包","零钱","提现","还款","信用卡","花呗","借呗","白条","亲情","转出","汇款","贷款")
    )

    private val INCOME_RULES: List<Pair<String, List<String>>> = listOf(
        "工资" to listOf("工资","薪资","薪酬","发薪","代发工资","年终奖","奖金"),
        "兼职" to listOf("兼职","劳务","稿费","佣金","外快"),
        "理财" to listOf("理财","基金","股息","分红","利息","余额宝","收益","赎回"),
        "红包" to listOf("红包","压岁钱","礼金"),
        "报销/退款" to listOf("报销","退款","退货","退回","退费","退单")
    )

    /** 返回 (分类, 是否有把握)。没把握时调用方应该把这一笔丢进"待确认分类"队列。 */
    fun classify(hayRaw: String, type: String): Pair<String, Boolean> {
        val fallback = if (type == "income") "其他收入" else "其他"
        val hay = NotificationParser.normalize(hayRaw)
        if (hay.isBlank()) return fallback to false

        val rules = if (type == "income") INCOME_RULES else EXPENSE_RULES
        var best: String? = null
        var bestLen = 0
        for ((cat, keywords) in rules) {
            for (kw in keywords) {
                val nk = NotificationParser.normalize(kw)
                if (nk.isNotEmpty() && hay.contains(nk) && nk.length > bestLen) {
                    best = cat
                    bestLen = nk.length
                }
            }
        }
        return (best ?: fallback) to (best != null)
    }
}
