/// 价格档位。文案在 i18n 里，这里只放不随语言变的东西。
///
/// 阶梯是有意这么定的：三年恰好是一年的两倍（等于买两年送一年），
/// 终生再翻一倍多。多数人扫一眼就会直接跳到终生档。

export type PlanId = 'year' | 'years3' | 'lifetime'

export type Plan = {
  id: PlanId
  price: string
  /** 划算程度，用来标出推荐档 */
  featured?: boolean
}

export const plans: Plan[] = [
  { id: 'year', price: '1.99' },
  { id: 'years3', price: '3.99' },
  { id: 'lifetime', price: '9.99', featured: true },
]
