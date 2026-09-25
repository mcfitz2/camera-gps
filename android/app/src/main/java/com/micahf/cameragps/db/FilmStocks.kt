package com.micahf.cameragps.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** The stock list a new install starts with: films in production as of 2026. */
object FilmStocks {
    val defaults: List<Stock> = listOf(
        // Kodak colour negative
        Stock(name = "Kodak Gold 200", iso = 200),
        Stock(name = "Kodak UltraMax 400", iso = 400),
        Stock(name = "Kodak ColorPlus 200", iso = 200),
        Stock(name = "Kodak Pro Image 100", iso = 100),
        Stock(name = "Kodak Portra 160", iso = 160),
        Stock(name = "Kodak Portra 400", iso = 400),
        Stock(name = "Kodak Portra 800", iso = 800),
        Stock(name = "Kodak Ektar 100", iso = 100),
        // Kodak slide and black and white
        Stock(name = "Kodak Ektachrome E100", iso = 100),
        Stock(name = "Kodak Tri-X 400", iso = 400),
        Stock(name = "Kodak T-Max 100", iso = 100),
        Stock(name = "Kodak T-Max 400", iso = 400),
        // Fujifilm
        Stock(name = "Fujifilm 200", iso = 200),
        Stock(name = "Fujifilm Velvia 50", iso = 50),
        Stock(name = "Fujifilm Velvia 100", iso = 100),
        Stock(name = "Fujifilm Provia 100F", iso = 100),
        Stock(name = "Fujifilm Acros 100 II", iso = 100),
        // CineStill (Kodak motion picture film, C-41)
        Stock(name = "CineStill 50D", iso = 50),
        Stock(name = "CineStill 400D", iso = 400),
        Stock(name = "CineStill 800T", iso = 800),
        // Ilford, Kentmere and Harman
        Stock(name = "Ilford HP5 Plus", iso = 400),
        Stock(name = "Ilford FP4 Plus", iso = 125),
        Stock(name = "Ilford Pan F Plus", iso = 50),
        Stock(name = "Ilford Delta 100", iso = 100),
        Stock(name = "Ilford Delta 400", iso = 400),
        Stock(name = "Ilford Delta 3200", iso = 3200),
        Stock(name = "Ilford XP2 Super", iso = 400),
        Stock(name = "Ilford SFX 200", iso = 200),
        Stock(name = "Ilford Ortho Plus", iso = 80),
        Stock(name = "Kentmere Pan 100", iso = 100),
        Stock(name = "Kentmere Pan 400", iso = 400),
        Stock(name = "Harman Phoenix II 200", iso = 200),
        // Foma
        Stock(name = "Fomapan 100", iso = 100),
        Stock(name = "Fomapan 200", iso = 200),
        Stock(name = "Fomapan 400", iso = 400),
        // Lomography
        Stock(name = "Lomography Color 400", iso = 400),
        Stock(name = "Lomography Color 800", iso = 800),
    )

    /** Adds the defaults, keeping any stock of the same name already listed. */
    fun insertDefaults(db: SupportSQLiteDatabase) {
        for (stock in defaults) {
            db.execSQL("INSERT OR IGNORE INTO stock (name, iso) VALUES (?, ?)", arrayOf<Any?>(stock.name, stock.iso))
        }
    }
}
