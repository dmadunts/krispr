package dev.krispr.playground.catalog

object Fixtures {
    val novel = Product("b1", "The Long Winter Novel", 1299, 12, Category.BOOKS, 4.4, 120, setOf("fiction", "winter"), ageDays = 400)
    val atlas = Product("b2", "Atlas of Rivers", 3500, 3, Category.BOOKS, 4.8, 15, setOf("maps"), ageDays = 10, salePriceCents = 2900)
    val vinyl = Product("m1", "Blue Hour Vinyl", 2499, 0, Category.MUSIC, 4.1, 44, setOf("jazz", "winter"), ageDays = 60)
    val chess = Product("g1", "Chess Set", 4000, 30, Category.GAMES, 3.9, 8, setOf("classic"), ageDays = 5)
    val drill = Product("t1", "Cordless Drill", 8999, 7, Category.TOOLS, 4.6, 230, setOf("power"), ageDays = 200, salePriceCents = 7999)
    val trowel = Product("h1", "Garden Trowel", 799, 50, Category.GARDEN, 0.0, 0, setOf("hand"), ageDays = 90)
    val all = listOf(novel, atlas, vinyl, chess, drill, trowel)
}
