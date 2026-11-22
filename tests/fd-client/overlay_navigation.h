#pragma once
#include <cstddef>

enum MenuPage {
    PAGE_HOME = 0,
    PAGE_DRAW,
    PAGE_ADVANCED,
    PAGE_SETTINGS,
    PAGE_LOG,
    PAGE_DEBUG,
    PAGE_TRACE,
    PAGE_PLAYER_SCAN,
    PAGE_WORLD_OBJECTS,
    PAGE_NETWORK,
    PAGE_EXIT = 99
};
namespace qingwei {
struct MenuEntry {
    int page;
    const char *label;
    const char *group;
};
struct MenuEntries {
    const MenuEntry *data;
    std::size_t count;
    bool Contains(int page) const {
        for (std::size_t i = 0; i < count; ++i)
            if (data[i].page == page)
                return true;
        return false;
    }
};
inline MenuEntries VisibleMenu(bool member, bool developer, bool loading) {
    static constexpr MenuEntry basic[] = {{PAGE_HOME, "首页", "常用"},
                                          {PAGE_ADVANCED, "高级", "常用"},
                                          {PAGE_SETTINGS, "设置", "应用"},
                                          {PAGE_EXIT, "退出", "应用"}};
    static constexpr MenuEntry normal[] = {{PAGE_HOME, "首页", "常用"},     {PAGE_DRAW, "绘制", "常用"},
                                           {PAGE_ADVANCED, "高级", "常用"}, {PAGE_LOG, "日志", "诊断"},
                                           {PAGE_SETTINGS, "设置", "应用"}, {PAGE_EXIT, "退出", "应用"}};
    static constexpr MenuEntry dev[] = {
        {PAGE_HOME, "首页", "常用"},        {PAGE_DRAW, "绘制", "常用"},
        {PAGE_ADVANCED, "高级", "常用"},    {PAGE_LOG, "日志", "开发"},
        {PAGE_DEBUG, "调试", "开发"},       {PAGE_TRACE, "跟踪", "开发"},
        {PAGE_PLAYER_SCAN, "扫描", "开发"}, {PAGE_WORLD_OBJECTS, "世界", "开发"},
        {PAGE_NETWORK, "网络", "开发"},     {PAGE_SETTINGS, "设置", "应用"},
        {PAGE_EXIT, "退出", "应用"}};
    if (!member || loading)
        return {basic, sizeof(basic) / sizeof(*basic)};
    if (developer)
        return {dev, sizeof(dev) / sizeof(*dev)};
    return {normal, sizeof(normal) / sizeof(*normal)};
}
inline int AccessiblePage(int page, bool member, bool developer, bool loading) {
    return VisibleMenu(member, developer, loading).Contains(page) && page != PAGE_EXIT ? page : PAGE_HOME;
}
inline bool PageNeedsTarget(int page) {
    return page == PAGE_DRAW || page == PAGE_DEBUG || page == PAGE_TRACE || page == PAGE_PLAYER_SCAN ||
           page == PAGE_WORLD_OBJECTS || page == PAGE_NETWORK;
}
} // namespace qingwei
