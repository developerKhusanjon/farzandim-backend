package uz.farzandim.shared.security

/** `@RolesAllowed` uchun konstantalar — satrni qo'lda yozishdan xatolar chiqadi. */
object Roles {
    const val SUPER_ADMIN = "SUPER_ADMIN"
    const val SCHOOL_ADMIN = "SCHOOL_ADMIN"

    /**
     * Rol emas, qo'shimcha guruh: `admin_level = SUPER` bo'lgan maktab adminining
     * tokenida `groups = [SCHOOL_ADMIN, SCHOOL_SUPER_ADMIN]` bo'ladi.
     */
    const val SCHOOL_SUPER_ADMIN = "SCHOOL_SUPER_ADMIN"
    const val TEACHER = "TEACHER"
    const val PARENT = "PARENT"
    const val STUDENT = "STUDENT"

    const val STAFF_ARRAY = "STAFF"
}
