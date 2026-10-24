type PlaceholderPageProps = {
  title: string
  description: string
}

function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return (
    <>
      <h1>{title}</h1>
      <p>{description}</p>
    </>
  )
}

export default PlaceholderPage
